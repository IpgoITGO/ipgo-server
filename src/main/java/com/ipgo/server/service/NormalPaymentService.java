package com.ipgo.server.service;

import com.ipgo.server.config.BillingProperties;
import com.ipgo.server.config.NormalPaymentProperties;
import com.ipgo.server.dto.response.BillingViews;
import com.ipgo.server.exception.BillingException;
import com.ipgo.server.infrastructure.BillingGateway;
import com.ipgo.server.infrastructure.BillingSecrets;
import com.ipgo.server.infrastructure.NormalPaymentGateway;
import com.ipgo.server.repository.BillingLedger;
import com.ipgo.server.repository.BillingLedger.Row;
import com.ipgo.server.util.BillingPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import static com.ipgo.server.repository.BillingLedger.at;

/** One-off payment authentication is client-side; approval and entitlement checks are server-side. */
@Service
public class NormalPaymentService {
    // #208 환불 제한 전(v1) 결제 동의 버전. 지금 받는 버전은 RefundPolicyProperties.consentVersion()이다.
    public static final String CONSENT=com.ipgo.server.config.RefundPolicyProperties.CONSENT_BEFORE;
    private final NormalPaymentProperties properties;
    private final BillingProperties billingProperties;
    private final BillingService billing;
    private final BillingLedger ledger;
    private final NormalPaymentGateway gateway;
    private final Clock clock;
    private final BillingSecrets secrets;
    private final com.ipgo.server.config.RefundPolicyProperties refundPolicy;
    public record Checkout(String orderId,String customerKey,long amount,String orderName,String successUrl,String failUrl,Instant expiresAt) { }
    public record Result(String orderId,String invoiceId,String status,String refundStatus,Instant canceledAt) { }
    public NormalPaymentService(NormalPaymentProperties p,BillingProperties bp,BillingService b,BillingLedger l,NormalPaymentGateway g,Clock c,BillingSecrets s,
            com.ipgo.server.config.RefundPolicyProperties r) {
        properties=p; billingProperties=bp; billing=b; ledger=l; gateway=g; clock=c; secrets=s; refundPolicy=r;
    }
    private <T> T locked(long user,Supplier<T> action) {
        if(!properties.enabled()) throw new BillingException(503,"PAYMENT_DISABLED","일반결제를 준비 중입니다.");
        return ledger.transaction(()->{ billing.subscription(user); return action.get(); });
    }
    private String uuid() { return UUID.randomUUID().toString(); }
    private Row owned(long user,String id) { return ledger.one("select * from billing_normal_orders where id=? and user_id=?",id,user); }
    private Row invoice(Row o) { return ledger.one("select * from billing_invoices where id=? and user_id=?",o.text("invoice_id"),o.number("user_id")); }
    private Result view(Row o) { return new Result(o.text("id"),o.text("invoice_id"),o.text("status"),o.text("refund_status"),o.time("canceled_at")); }
    private Checkout checkout(Row o) {
        Row i=invoice(o); String origin=billingProperties.returnOrigin();
        return new Checkout(o.text("id"),o.text("customer_key"),i.number("amount"),i.text("order_name"),
                origin+"/payment/success",origin+"/payment/fail",o.time("expires_at"));
    }
    public Checkout create(long user,String key,String planId,String version,String consent) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}")) throw BillingException.invalid();
        // #208 지금 결제 화면 고지에 맞는 동의 버전만 받는다(환불 제한을 켜면 v2).
        if(!refundPolicy.consentVersion().equals(consent)) throw BillingException.conflict("BILLING_CONSENT_REQUIRED");
        var plan=BillingPolicy.plan(planId);
        if("trial".equals(planId) || !plan.priceVersion().equals(version)) throw BillingException.conflict("PRICE_CHANGED");
        String hash=BillingSecrets.hash(planId+"|"+version+"|"+consent);
        return locked(user,()->{
            var same=ledger.list("select * from billing_normal_orders where user_id=? and command_key=?",user,key);
            if(!same.isEmpty()) {
                if(!hash.equals(same.getFirst().text("body_hash"))) throw BillingException.conflict("IDEMPOTENCY_KEY_REUSED");
                if(!"OPEN".equals(same.getFirst().text("status"))) throw BillingException.conflict("PAYMENT_ALREADY_STARTED");
                if(!clock.instant().isBefore(same.getFirst().time("expires_at"))) throw BillingException.conflict("PAYMENT_EXPIRED");
                return checkout(same.getFirst());
            }
            for(Row stale:ledger.list("select * from billing_normal_orders where user_id=? and status='OPEN' and expires_at<=?",user,at(clock.instant()))) {
                ledger.update("update billing_normal_orders set status='FAILED' where id=?",stale.text("id"));
                ledger.update("update billing_invoices set status='VOID' where id=?",stale.text("invoice_id"));
            }
            if(!ledger.list("select id from billing_normal_orders where user_id=? and (status in ('CONFIRMING','PENDING','UNKNOWN','REVIEW_REQUIRED') or refund_status in ('PROCESSING','UNKNOWN','ACCOUNT_REQUIRED','REVIEW_REQUIRED'))",user).isEmpty()) throw BillingException.conflict("BILLING_IN_PROGRESS");
            if(!ledger.list("select id from billing_attempts where user_id=? and status in ('READY','DISPATCHING','UNKNOWN','REVIEW_REQUIRED')",user).isEmpty()
                    || !ledger.list("select id from billing_refunds where user_id=? and status<>'CANCELED'",user).isEmpty()) throw BillingException.conflict("BILLING_IN_PROGRESS");
            Row sub=ledger.one("select * from billing_subscriptions where user_id=?",user);
            if("ACTIVE".equals(sub.text("status")) && sub.time("period_end")!=null && clock.instant().isBefore(sub.time("period_end"))) throw BillingException.conflict("ALREADY_ACTIVE");
            var open=ledger.list("select * from billing_normal_orders where user_id=? and status='OPEN'",user);
            if(!open.isEmpty()) {
                if(!hash.equals(open.getFirst().text("body_hash"))) throw BillingException.conflict("BILLING_IN_PROGRESS");
                return checkout(open.getFirst());
            }
            String id="normal_"+uuid(),inv=uuid(); Instant start=clock.instant();
            long cycle=ledger.one("select coalesce(max(cycle_no),0)+1 as n from billing_invoices where user_id=?",user).number("n");
            ledger.update("insert into billing_invoices(id,user_id,cycle_no,plan_id,price_version,amount,order_name,currency,period_start,period_end,status) values (?,?,?,?,?,?,?,'KRW',?,?,'OPEN')",
                    inv,user,cycle,planId,version,plan.amount(),"Ipgo "+planId+" 1개월 이용권",at(start),at(BillingPolicy.boundary(start,1)));
            // #208 주문마다 회원이 동의한 결제 동의 버전과 시각을 남긴다.
            ledger.update("insert into billing_normal_orders(id,user_id,invoice_id,command_key,body_hash,customer_key,status,provider_key,created_at,expires_at,consent_version,consented_at) values (?,?,?,?,?,?,'OPEN',?,?,?,?,?)",
                    id,user,inv,key,hash,uuid(),uuid(),at(start),at(start.plusSeconds(1800)),consent,at(start));
            return checkout(owned(user,id));
        });
    }
    public Result confirm(long user,String id,String paymentKey,long amount) {
        if(paymentKey==null || paymentKey.isBlank() || paymentKey.length()>200) throw BillingException.invalid();
        boolean claim=locked(user,()->{
            Row o=owned(user,id); Row i=invoice(o);
            if(amount!=i.number("amount")) throw BillingException.conflict("PAYMENT_AMOUNT_MISMATCH");
            if(o.text("payment_key")!=null && !paymentKey.equals(o.text("payment_key"))) throw BillingException.conflict("PAYMENT_KEY_CHANGED");
            if(!"OPEN".equals(o.text("status"))) return false;
            if(!clock.instant().isBefore(o.time("expires_at"))) throw BillingException.conflict("PAYMENT_EXPIRED");
            ledger.update("update billing_normal_orders set status='CONFIRMING',payment_key=?,lease_until=? where id=?",paymentKey,at(clock.instant().plusSeconds(180)),id);
            return true;
        });
        if(claim) perform(user,id,true);
        return get(user,id);
    }
    public Result get(long user,String id) {
        Row o=locked(user,()->owned(user,id));
        if(o.text("refund_status")==null && List.of("CONFIRMING","UNKNOWN","PENDING").contains(o.text("status")) && (o.time("lease_until")==null || !clock.instant().isBefore(o.time("lease_until")))) {
            boolean claim=locked(user,()->{
                Row fresh=owned(user,id);
                if(!List.of("CONFIRMING","UNKNOWN","PENDING").contains(fresh.text("status")) || (fresh.time("lease_until")!=null && clock.instant().isBefore(fresh.time("lease_until")))) return false;
                ledger.update("update billing_normal_orders set lease_until=? where id=?",at(clock.instant().plusSeconds(180)),id); return true;
            });
            if(claim) {
                if(!clock.instant().isBefore(o.time("created_at").plus(Duration.ofDays(14)))) locked(user,()->{ review(id); return null; });
                else perform(user,id,false);
            }
        }
        Row refund=locked(user,()->owned(user,id));
        if(List.of("PROCESSING","UNKNOWN").contains(refund.text("refund_status")==null?"":refund.text("refund_status"))
                && refund.time("lease_until")!=null && !clock.instant().isBefore(refund.time("lease_until"))) {
            boolean claim=locked(user,()->{
                Row fresh=owned(user,id);
                if(fresh.time("lease_until")==null || clock.instant().isBefore(fresh.time("lease_until"))) return false;
                if(!clock.instant().isBefore(fresh.time("refund_started_at").plus(Duration.ofDays(14)))) {
                    refundReview(id); return false;
                }
                ledger.update("update billing_normal_orders set lease_until=? where id=?",at(clock.instant().plusSeconds(180)),id); return true;
            });
            if(claim) performRefund(user,id,true);
        }
        return locked(user,()->view(owned(user,id)));
    }
    public List<Result> pending(long user) {
        return locked(user,()->ledger.list("select * from billing_normal_orders where user_id=? and (status in ('CONFIRMING','PENDING','UNKNOWN','REVIEW_REQUIRED') or refund_status in ('PROCESSING','UNKNOWN','ACCOUNT_REQUIRED','REVIEW_REQUIRED')) order by created_at",user).stream().map(this::view).toList());
    }
    private boolean matches(BillingGateway.Payment p,Row o,Row i) {
        return p!=null && o.text("id").equals(p.orderId()) && o.text("payment_key").equals(p.paymentKey())
                && i.number("amount")==p.totalAmount() && "KRW".equals(p.currency()) && p.merchantId()!=null && !p.merchantId().isBlank()
                && (properties.merchantId().isBlank() || properties.merchantId().equals(p.merchantId()))
                && (o.text("merchant_id")==null || o.text("merchant_id").equals(p.merchantId()));
    }
    private void perform(long user,String id,boolean confirm) {
        Row o=owned(user,id),i=invoice(o);
        try {
            var p=confirm?gateway.confirm(o.text("payment_key"),id,i.number("amount"),o.text("provider_key")):gateway.lookup(o.text("payment_key"));
            locked(user,()->{
                Row fresh=owned(user,id);
                if(!List.of("CONFIRMING","UNKNOWN","PENDING").contains(fresh.text("status"))) return null;
                if(!matches(p,fresh,i)) { review(id); return null; }
                if(p.method()!=null && !p.method().isBlank()) ledger.update("update billing_normal_orders set payment_method=? where id=?",p.method(),id);
                if("DONE".equals(p.status()) && p.balanceAmount()==i.number("amount") && p.approvedAt()!=null
                        && !p.approvedAt().isAfter(clock.instant().plusSeconds(60)) && !p.approvedAt().isBefore(o.time("created_at"))) {
                    Instant start=p.approvedAt(),end=BillingPolicy.boundary(start,1);
                    ledger.update("update billing_normal_orders set status='PAID',merchant_id=?,lease_until=case when refund_status is null then null else lease_until end where id=?",p.merchantId(),id);
                    ledger.update("update billing_invoices set status='PAID',paid_at=?,period_start=?,period_end=? where id=?",at(start),at(start),at(end),i.text("id"));
                    ledger.update("update billing_subscriptions set status='ACTIVE',plan_id=?,price_version=?,amount=?,period_start=?,period_end=?,anchor_at=?,cycle_no=?,cancel_at_period_end=true,next_charge_at=null,default_method_id=null where user_id=?",
                            i.text("plan_id"),i.text("price_version"),i.number("amount"),at(start),at(end),at(start),i.number("cycle_no"),user);
                } else if("WAITING_FOR_DEPOSIT".equals(p.status())) {
                    ledger.update("update billing_normal_orders set status='PENDING',merchant_id=?,lease_until=? where id=?",p.merchantId(),at(clock.instant().plusSeconds(60)),id);
                } else if(List.of("ABORTED","EXPIRED","CANCELED").contains(p.status())) {
                    ledger.update("update billing_normal_orders set status='FAILED',lease_until=null where id=?",id);
                    ledger.update("update billing_invoices set status='VOID' where id=?",i.text("id"));
                } else unknown(id);
                return null;
            });
        } catch(BillingGateway.Failure e) { locked(user,()->{
            if(confirm && e.rejected()) {
                ledger.update("update billing_normal_orders set status='FAILED',lease_until=null where id=?",id);
                ledger.update("update billing_invoices set status='VOID' where id=?",i.text("id"));
            } else unknown(id);
            return null;
        }); }
        // A DB failure leaves CONFIRMING + lease. Recovery only queries the existing paymentKey.
    }
    private void unknown(String id) { ledger.update("update billing_normal_orders set status='UNKNOWN',lease_until=? where id=?",at(clock.instant().plusSeconds(60)),id); }
    private void review(String id) { ledger.update("update billing_normal_orders set status='REVIEW_REQUIRED',lease_until=null where id=?",id); }
    public boolean ownsInvoice(long user,String id) { return properties.enabled() && !ledger.list("select id from billing_normal_orders where invoice_id=? and user_id=?",id,user).isEmpty(); }
    public BillingViews.Refund refund(long user,String invoiceId,String key,String reason) {
        return refund(user,invoiceId,key,reason,null);
    }
    public BillingViews.Refund refund(long user,String invoiceId,String key,String reason,NormalPaymentGateway.RefundAccount account) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}") || reason==null || reason.isBlank() || reason.length()>200) throw BillingException.invalid();
        String payload=account==null?null:account.bank()+"\n"+account.accountNumber()+"\n"+account.holderName();
        String fingerprint=payload==null?null:secrets.fingerprint(payload);
        String id=locked(user,()->{
            Row o=ledger.one("select * from billing_normal_orders where invoice_id=? and user_id=?",invoiceId,user);
            Row i=invoice(o); Row sub=ledger.one("select * from billing_subscriptions where user_id=?",user);
            if(o.text("refund_reason")!=null && !reason.equals(o.text("refund_reason")) && !"ACCOUNT_REQUIRED".equals(o.text("refund_status"))) throw BillingException.conflict("REFUND_REASON_CHANGED");
            if(o.text("refund_status")!=null && !"ACCOUNT_REQUIRED".equals(o.text("refund_status"))) {
                if(payload!=null && !fingerprint.equals(o.text("refund_account_fingerprint")) && List.of("PROCESSING","UNKNOWN").contains(o.text("refund_status")))
                    throw BillingException.conflict("REFUND_DETAILS_CHANGED");
                return null;
            }
            boolean paid="PAID".equals(o.text("status")) && i.number("cycle_no")==sub.number("cycle_no");
            // #208 환불 제한: 결제 완료 건의 새 환불 요청은 받지 않는다. 진행 중인 환불(ACCOUNT_REQUIRED 계좌 재입력 포함)과
            // 입금 전 가상계좌 취소(PENDING)는 그대로 처리한다. 회사 귀책 환급은 운영자가 토스 관리자 화면에서 한다.
            if(refundPolicy.restricted() && "PAID".equals(o.text("status")) && o.text("refund_status")==null) throw BillingException.conflict("REFUND_NOT_ALLOWED");
            if(!paid && !"PENDING".equals(o.text("status"))) throw BillingException.conflict("ONLY_CURRENT_PAYMENT_REFUNDABLE");
            if(pending(user).stream().anyMatch(row->!row.orderId().equals(o.text("id")))) throw BillingException.conflict("BILLING_IN_PROGRESS");
            if(account!=null && o.text("payment_method")!=null && !"가상계좌".equals(o.text("payment_method"))) throw BillingException.invalid();
            ledger.update("update billing_normal_orders set refund_status='PROCESSING',refund_reason=?,refund_key=?,refund_started_at=?,lease_until=?,refund_account_ciphertext=?,refund_account_fingerprint=? where id=?",
                    reason,uuid(),at(clock.instant()),at(clock.instant().plusSeconds(180)),payload==null?null:secrets.encrypt(payload),fingerprint,o.text("id"));
            return o.text("id");
        });
        if(id!=null) performRefund(user,id,true);
        return locked(user,()->{
            Row o=ledger.one("select * from billing_normal_orders where invoice_id=? and user_id=?",invoiceId,user);
            return new BillingViews.Refund(invoiceId,o.text("refund_status"),o.time("canceled_at"));
        });
    }
    private void refundReview(String id) {
        ledger.update("update billing_normal_orders set refund_status='REVIEW_REQUIRED',lease_until=null,refund_account_ciphertext=null,refund_account_fingerprint=null where id=?",id);
    }
    private void accountRequired(String id) {
        ledger.update("update billing_normal_orders set refund_status='ACCOUNT_REQUIRED',lease_until=null,refund_account_ciphertext=null,refund_account_fingerprint=null where id=?",id);
    }
    private NormalPaymentGateway.RefundAccount refundAccount(Row o) {
        if(o.text("refund_account_ciphertext")==null) return null;
        String[] parts=secrets.decrypt(o.text("refund_account_ciphertext")).split("\n",-1);
        if(parts.length!=3) throw new IllegalStateException("Invalid encrypted refund account");
        return new NormalPaymentGateway.RefundAccount(parts[0],parts[1],parts[2]);
    }
    private void performRefund(long user,String id,boolean cancel) {
        try {
            Row initial=owned(user,id),initialInvoice=invoice(initial);
            var p=gateway.lookup(initial.text("payment_key"));
            if(!matches(p,initial,initialInvoice)) { locked(user,()->{ refundReview(id); return null; }); return; }
            String method=p.method();
            if(method!=null && !method.isBlank()) locked(user,()->{ ledger.update("update billing_normal_orders set payment_method=? where id=?",method,id); return null; });
            // A deposit racing an unpaid-account cancellation must first be reconciled.
            // This only queries; the existing paid path grants exactly one period.
            if("DONE".equals(p.status()) && initialInvoice.time("paid_at")==null) {
                perform(user,id,false);
                if(invoice(owned(user,id)).time("paid_at")==null) { locked(user,()->{ refundReview(id); return null; }); return; }
            }
            Row o=owned(user,id),i=invoice(o);
            boolean virtual="가상계좌".equals(p.method()) || "가상계좌".equals(o.text("payment_method"));
            if(cancel && ("DONE".equals(p.status()) || "WAITING_FOR_DEPOSIT".equals(p.status()))) {
                if(("DONE".equals(p.status()) && p.balanceAmount()!=i.number("amount")) || ("WAITING_FOR_DEPOSIT".equals(p.status()) && !virtual)) {
                    locked(user,()->{ refundReview(id); return null; }); return;
                }
                var account=refundAccount(o);
                if(virtual && "DONE".equals(p.status()) && account==null) { locked(user,()->{ accountRequired(id); return null; }); return; }
                p=virtual && "DONE".equals(p.status())?gateway.cancel(p.paymentKey(),o.text("refund_reason"),o.text("refund_key"),account)
                        :gateway.cancel(p.paymentKey(),o.text("refund_reason"),o.text("refund_key"));
            }
            var result=p;
            locked(user,()->{
                Row fresh=owned(user,id),inv=invoice(fresh);
                Instant earliest=inv.time("paid_at")==null?fresh.time("created_at"):inv.time("paid_at");
                if(matches(result,fresh,inv) && "CANCELED".equals(result.status()) && result.balanceAmount()==0 && result.canceledAt()!=null
                        && !result.canceledAt().isBefore(earliest) && !result.canceledAt().isAfter(clock.instant().plusSeconds(60))) {
                    boolean funded=inv.time("paid_at")!=null || result.approvedAt()!=null;
                    String status="CANCELED";
                    if(virtual && funded && !"COMPLETED".equals(result.virtualRefundStatus()))
                        status=List.of("FAILED","PARTIAL_FAILED").contains(result.virtualRefundStatus()==null?"":result.virtualRefundStatus())?"REVIEW_REQUIRED":"PROCESSING";
                    ledger.update("update billing_normal_orders set status='CANCELED',refund_status=?,canceled_at=?,lease_until=?,refund_account_ciphertext=null,refund_account_fingerprint=? where id=?",
                            status,at(result.canceledAt()),"PROCESSING".equals(status)?at(clock.instant().plusSeconds(60)):null,"PROCESSING".equals(status)?fresh.text("refund_account_fingerprint"):null,id);
                    if(inv.time("paid_at")==null && result.approvedAt()!=null && !result.approvedAt().isBefore(fresh.time("created_at")) && !result.approvedAt().isAfter(result.canceledAt()))
                        ledger.update("update billing_invoices set paid_at=? where id=?",at(result.approvedAt()),inv.text("id"));
                    ledger.update("update billing_invoices set status='VOID' where id=?",inv.text("id"));
                    if(funded) ledger.update("update billing_subscriptions set status='CANCELED',cancel_at_period_end=true,next_charge_at=null where user_id=? and cycle_no=?",user,inv.number("cycle_no"));
                } else ledger.update("update billing_normal_orders set refund_status='UNKNOWN',lease_until=? where id=?",at(clock.instant().plusSeconds(60)),id);
                return null;
            });
        } catch(BillingGateway.Failure e) {
            locked(user,()->{ if(e.rejected() && "REFUND_ACCOUNT_INVALID".equals(e.code())) accountRequired(id);
                else if(e.rejected() && "REFUND_REJECTED".equals(e.code())) refundReview(id);
                else ledger.update("update billing_normal_orders set refund_status='UNKNOWN',lease_until=? where id=?",at(clock.instant().plusSeconds(60)),id); return null; });
        } catch(IllegalStateException e) { locked(user,()->{ refundReview(id); return null; }); }
    }
    public void processOne() {
        if(!properties.enabled()) return;
        var work=ledger.list("select * from billing_normal_orders where (status in ('CONFIRMING','UNKNOWN','PENDING') or refund_status in ('PROCESSING','UNKNOWN')) and lease_until<=? order by lease_until,created_at,id limit 1",at(clock.instant()));
        if(work.isEmpty()) return;
        Row o=work.getFirst(); long user=o.number("user_id");
        if(o.text("refund_status")!=null) {
            boolean claim=locked(user,()->{
                Row fresh=owned(user,o.text("id"));
                if(!List.of("PROCESSING","UNKNOWN").contains(fresh.text("refund_status")) || clock.instant().isBefore(fresh.time("lease_until"))) return false;
                if(!clock.instant().isBefore(fresh.time("refund_started_at").plus(Duration.ofDays(14)))) { refundReview(o.text("id")); return false; }
                ledger.update("update billing_normal_orders set lease_until=? where id=?",at(clock.instant().plusSeconds(180)),o.text("id")); return true;
            });
            if(claim) performRefund(user,o.text("id"),true);
        } else get(user,o.text("id"));
    }
}
