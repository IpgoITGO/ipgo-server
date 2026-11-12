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
    public static final String CONSENT="one-time-month-2026-11-v1";
    private final NormalPaymentProperties properties;
    private final BillingProperties billingProperties;
    private final BillingService billing;
    private final BillingLedger ledger;
    private final NormalPaymentGateway gateway;
    private final Clock clock;
    public record Checkout(String orderId,String customerKey,long amount,String orderName,String successUrl,String failUrl,Instant expiresAt) { }
    public record Result(String orderId,String invoiceId,String status,String refundStatus,Instant canceledAt) { }
    public NormalPaymentService(NormalPaymentProperties p,BillingProperties bp,BillingService b,BillingLedger l,NormalPaymentGateway g,Clock c) {
        properties=p; billingProperties=bp; billing=b; ledger=l; gateway=g; clock=c;
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
        if(!CONSENT.equals(consent)) throw BillingException.conflict("BILLING_CONSENT_REQUIRED");
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
            if(!ledger.list("select id from billing_normal_orders where user_id=? and (status in ('CONFIRMING','PENDING','UNKNOWN','REVIEW_REQUIRED') or refund_status in ('PROCESSING','UNKNOWN','REVIEW_REQUIRED'))",user).isEmpty()) throw BillingException.conflict("BILLING_IN_PROGRESS");
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
            ledger.update("insert into billing_normal_orders(id,user_id,invoice_id,command_key,body_hash,customer_key,status,provider_key,created_at,expires_at) values (?,?,?,?,?,?,'OPEN',?,?,?)",
                    id,user,inv,key,hash,uuid(),uuid(),at(start),at(start.plusSeconds(1800)));
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
        if(List.of("CONFIRMING","UNKNOWN","PENDING").contains(o.text("status")) && (o.time("lease_until")==null || !clock.instant().isBefore(o.time("lease_until")))) {
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
                    ledger.update("update billing_normal_orders set refund_status='REVIEW_REQUIRED',lease_until=null where id=?",id); return false;
                }
                ledger.update("update billing_normal_orders set lease_until=? where id=?",at(clock.instant().plusSeconds(180)),id); return true;
            });
            if(claim) performRefund(user,id,true);
        }
        return locked(user,()->view(owned(user,id)));
    }
    public List<Result> pending(long user) {
        return locked(user,()->ledger.list("select * from billing_normal_orders where user_id=? and (status in ('CONFIRMING','PENDING','UNKNOWN','REVIEW_REQUIRED') or refund_status in ('PROCESSING','UNKNOWN','REVIEW_REQUIRED')) order by created_at",user).stream().map(this::view).toList());
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
                if("DONE".equals(p.status()) && p.balanceAmount()==i.number("amount") && p.approvedAt()!=null
                        && !p.approvedAt().isAfter(clock.instant().plusSeconds(60)) && !p.approvedAt().isBefore(o.time("created_at"))) {
                    Instant start=p.approvedAt(),end=BillingPolicy.boundary(start,1);
                    ledger.update("update billing_normal_orders set status='PAID',merchant_id=?,lease_until=null where id=?",p.merchantId(),id);
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
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}") || reason==null || reason.isBlank() || reason.length()>200) throw BillingException.invalid();
        String id=locked(user,()->{
            Row o=ledger.one("select * from billing_normal_orders where invoice_id=? and user_id=?",invoiceId,user);
            Row i=invoice(o); Row sub=ledger.one("select * from billing_subscriptions where user_id=?",user);
            if(o.text("refund_reason")!=null && !reason.equals(o.text("refund_reason"))) throw BillingException.conflict("REFUND_REASON_CHANGED");
            if(o.text("refund_status")!=null) return null;
            if(!"PAID".equals(o.text("status")) || i.number("cycle_no")!=sub.number("cycle_no")) throw BillingException.conflict("ONLY_CURRENT_PAYMENT_REFUNDABLE");
            if(!pending(user).isEmpty()) throw BillingException.conflict("BILLING_IN_PROGRESS");
            ledger.update("update billing_normal_orders set refund_status='PROCESSING',refund_reason=?,refund_key=?,refund_started_at=?,lease_until=? where id=?",reason,uuid(),at(clock.instant()),at(clock.instant().plusSeconds(180)),o.text("id")); return o.text("id");
        });
        if(id!=null) performRefund(user,id,true);
        return locked(user,()->{
            Row o=ledger.one("select * from billing_normal_orders where invoice_id=? and user_id=?",invoiceId,user);
            return new BillingViews.Refund(invoiceId,o.text("refund_status"),o.time("canceled_at"));
        });
    }
    private void performRefund(long user,String id,boolean cancel) {
        Row o=owned(user,id),i=invoice(o);
        try {
            var p=gateway.lookup(o.text("payment_key"));
            if(matches(p,o,i) && "DONE".equals(p.status()) && p.balanceAmount()==i.number("amount") && cancel) p=gateway.cancel(p.paymentKey(),o.text("refund_reason"),o.text("refund_key"));
            var result=p;
            locked(user,()->{
                if(matches(result,o,i) && "CANCELED".equals(result.status()) && result.balanceAmount()==0 && result.canceledAt()!=null
                        && !result.canceledAt().isBefore(i.time("paid_at")) && !result.canceledAt().isAfter(clock.instant().plusSeconds(60))) {
                    ledger.update("update billing_normal_orders set status='CANCELED',refund_status='CANCELED',canceled_at=?,lease_until=null where id=?",at(result.canceledAt()),id);
                    ledger.update("update billing_invoices set status='VOID' where id=?",i.text("id"));
                    ledger.update("update billing_subscriptions set status='CANCELED',cancel_at_period_end=true,next_charge_at=null where user_id=? and cycle_no=?",user,i.number("cycle_no"));
                } else ledger.update("update billing_normal_orders set refund_status='UNKNOWN',lease_until=? where id=?",at(clock.instant().plusSeconds(60)),id);
                return null;
            });
        } catch(BillingGateway.Failure e) { locked(user,()->{ ledger.update("update billing_normal_orders set refund_status='UNKNOWN',lease_until=? where id=?",at(clock.instant().plusSeconds(60)),id); return null; }); }
    }
    public void processOne() {
        if(!properties.enabled()) return;
        var work=ledger.list("select * from billing_normal_orders where (status in ('CONFIRMING','UNKNOWN','PENDING') or refund_status in ('PROCESSING','UNKNOWN')) and lease_until<=? order by created_at limit 1",at(clock.instant()));
        if(work.isEmpty()) return;
        Row o=work.getFirst(); long user=o.number("user_id");
        if(o.text("refund_status")!=null) {
            boolean claim=locked(user,()->{
                Row fresh=owned(user,o.text("id"));
                if(!List.of("PROCESSING","UNKNOWN").contains(fresh.text("refund_status")) || clock.instant().isBefore(fresh.time("lease_until"))) return false;
                if(!clock.instant().isBefore(fresh.time("refund_started_at").plus(Duration.ofDays(14)))) { ledger.update("update billing_normal_orders set refund_status='REVIEW_REQUIRED',lease_until=null where id=?",o.text("id")); return false; }
                ledger.update("update billing_normal_orders set lease_until=? where id=?",at(clock.instant().plusSeconds(180)),o.text("id")); return true;
            });
            if(claim) performRefund(user,o.text("id"),true);
        } else get(user,o.text("id"));
    }
}
