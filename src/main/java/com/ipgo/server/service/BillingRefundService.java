package com.ipgo.server.service;

import com.ipgo.server.config.BillingProperties;
import com.ipgo.server.dto.response.BillingViews;
import com.ipgo.server.exception.BillingException;
import com.ipgo.server.infrastructure.BillingGateway;
import com.ipgo.server.infrastructure.BillingSecrets;
import com.ipgo.server.repository.BillingLedger;
import com.ipgo.server.repository.BillingLedger.Row;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import static com.ipgo.server.repository.BillingLedger.at;

/** 본인 최신 결제의 전액 취소. 승인 원장은 보존하고 불확정 취소는 동일 주문/멱등키로 복구한다. */
@Service
public class BillingRefundService {
    private final BillingLedger ledger;
    private final BillingProperties properties;
    private final BillingGateway gateway;
    private final Clock clock;
    public BillingRefundService(BillingLedger ledger,BillingProperties properties,BillingGateway gateway,Clock clock) {
        this.ledger=ledger; this.properties=properties; this.gateway=gateway; this.clock=clock;
    }
    private <T> T locked(long user,Supplier<T> action) {
        if(!properties.enabled() || !"TEST".equals(properties.mode()))
            throw new BillingException(503,"BILLING_DISABLED","테스트 결제 기능을 준비 중입니다.");
        return ledger.transaction(()->{ ledger.lock(user); return action.get(); });
    }
    public BillingViews.Refund cancel(long user,String invoiceId,String key,String reason) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}") || reason==null || reason.isBlank() || reason.length()>200)
            throw BillingException.invalid();
        String operation="REFUND:"+invoiceId,hash=BillingSecrets.hash(invoiceId+"|"+reason);
        String work=locked(user,()->{
            Row invoice=ownedInvoice(user,invoiceId);
            var commands=ledger.list("select * from billing_commands where user_id=? and operation=? and command_key=?",user,operation,key);
            if(!commands.isEmpty() && !hash.equals(commands.getFirst().text("body_hash")))
                throw BillingException.conflict("IDEMPOTENCY_KEY_REUSED");
            var refunds=ledger.list("select * from billing_refunds where invoice_id=? and user_id=?",invoiceId,user);
            if(!refunds.isEmpty()) {
                Row existing=refunds.getFirst();
                if(!reason.equals(existing.text("reason"))) throw BillingException.conflict("REFUND_REASON_CHANGED");
                if(commands.isEmpty()) remember(user,operation,key,hash,existing.text("id"));
                if("CANCELED".equals(existing.text("status")) || "REVIEW_REQUIRED".equals(existing.text("status"))
                        || (existing.time("lease_until")!=null && clock.instant().isBefore(existing.time("lease_until")))) return null;
                claim(existing.text("id")); return existing.text("id");
            }
            Row sub=ledger.one("select * from billing_subscriptions where user_id=?",user);
            if(!"TEST".equals(sub.text("mode")) || !properties.merchantId().equals(sub.text("merchant_id")))
                throw BillingException.conflict("BILLING_MERCHANT_CHANGED");
            if(!"PAID".equals(invoice.text("status")) || invoice.number("cycle_no")!=sub.number("cycle_no"))
                throw BillingException.conflict("ONLY_CURRENT_PAYMENT_REFUNDABLE");
            if(!ledger.list("select id from billing_attempts where user_id=? and status in ('DISPATCHING','UNKNOWN','REVIEW_REQUIRED')",user).isEmpty())
                throw BillingException.conflict("BILLING_IN_PROGRESS");
            Row attempt=approved(invoiceId,user);
            if(attempt.text("payment_key")==null) throw BillingException.conflict("PAYMENT_NOT_APPROVED");
            String id=UUID.randomUUID().toString();
            ledger.update("insert into billing_refunds(id,invoice_id,user_id,reason,provider_key,status,created_at,lease_until) values (?,?,?,?,?,'PROCESSING',?,?)",
                    id,invoiceId,user,reason,UUID.randomUUID().toString(),at(clock.instant()),at(clock.instant().plusSeconds(180)));
            remember(user,operation,key,hash,id);
            // 취소 결과가 불확정이어도 다음 청구를 만들지 않는다. 이미 승인된 금액/권한은 확인 전 보존한다.
            ledger.update("update billing_subscriptions set cancel_at_period_end=true,next_charge_at=null where user_id=?",user);
            ledger.update("update billing_sessions set status='CANCELED' where user_id=? and purpose='START_SUBSCRIPTION' and status in ('OPEN','ISSUING')",user);
            ledger.update("update billing_attempts set status='VOID' where user_id=? and status in ('READY','DECLINED')",user);
            ledger.update("update billing_invoices set status='VOID' where user_id=? and status='OPEN'",user);
            return id;
        });
        if(work!=null) perform(work,user);
        return locked(user,()->view(ledger.one("select * from billing_refunds where invoice_id=? and user_id=?",invoiceId,user)));
    }
    private void remember(long user,String op,String key,String hash,String id) {
        ledger.update("insert into billing_commands(user_id,operation,command_key,body_hash,result_id,created_at) values (?,?,?,?,?,?)",user,op,key,hash,id,at(clock.instant()));
    }
    private Row ownedInvoice(long user,String invoice) {
        var rows=ledger.list("select * from billing_invoices where id=? and user_id=?",invoice,user);
        if(rows.isEmpty()) throw BillingException.forbidden();
        return rows.getFirst();
    }
    private Row approved(String invoice,long user) {
        return ledger.one("select * from billing_attempts where invoice_id=? and user_id=? and status='SUCCEEDED'",invoice,user);
    }
    private BillingViews.Refund view(Row row) { return new BillingViews.Refund(row.text("invoice_id"),row.text("status"),row.time("canceled_at")); }
    private void claim(String id) { ledger.update("update billing_refunds set status='PROCESSING',lease_until=? where id=?",at(clock.instant().plusSeconds(180)),id); }
    public void processOne() {
        var rows=ledger.list("select * from billing_refunds where status in ('PROCESSING','UNKNOWN') and lease_until<=? order by created_at limit 1",at(clock.instant()));
        if(rows.isEmpty()) return;
        Row row=rows.getFirst(); long user=row.number("user_id"); String id=row.text("id");
        boolean claimed=locked(user,()->{
            Row current=ledger.one("select * from billing_refunds where id=?",id);
            if(!java.util.List.of("PROCESSING","UNKNOWN").contains(current.text("status")) || clock.instant().isBefore(current.time("lease_until"))) return false;
            if(!clock.instant().isBefore(current.time("created_at").plus(Duration.ofDays(14)))) {
                ledger.update("update billing_refunds set status='REVIEW_REQUIRED',lease_until=null where id=?",id); return false;
            }
            claim(id); return true;
        });
        if(claimed) perform(id,user);
    }
    private void perform(String id,long user) {
        Row refund=ledger.one("select * from billing_refunds where id=? and user_id=?",id,user);
        Row invoice=ownedInvoice(user,refund.text("invoice_id"));
        Row attempt=approved(invoice.text("id"),user);
        try {
            // 주문 조회로 과거 버전에서 소문자로 저장된 paymentKey도 원래 대소문자로 복구한다.
            BillingGateway.Payment payment=gateway.lookup(attempt.text("order_id"));
            if(!matches(payment,invoice,attempt)) { uncertain(id,user); return; }
            if("DONE".equals(payment.status()) && payment.balanceAmount()==invoice.number("amount"))
                payment=gateway.cancel(payment.paymentKey(),refund.text("reason"),refund.text("provider_key"));
            if(!matches(payment,invoice,attempt) || !"CANCELED".equals(payment.status()) || payment.balanceAmount()!=0
                    || payment.canceledAt()==null || payment.canceledAt().isAfter(clock.instant().plusSeconds(60))
                    || payment.canceledAt().isBefore(invoice.time("paid_at"))) { uncertain(id,user); return; }
            var confirmed=payment;
            locked(user,()->{
                // user row lock과 claim lease로 중복 호출을 직렬화한다. DB 실패 시 lease 만료 후 조회 복구.
                ledger.update("update billing_refunds set status='CANCELED',canceled_at=?,lease_until=null where id=?",at(confirmed.canceledAt()),id);
                ledger.update("update billing_invoices set status='VOID' where id=? and user_id=?",invoice.text("id"),user);
                ledger.update("update billing_subscriptions set status='CANCELED',cancel_at_period_end=true,next_charge_at=null where user_id=? and cycle_no=?",
                        user,invoice.number("cycle_no"));
                return null;
            });
        } catch(BillingGateway.Failure e) { uncertain(id,user); }
    }
    private boolean matches(BillingGateway.Payment p,Row invoice,Row attempt) {
        return p!=null && attempt.text("order_id").equals(p.orderId()) && invoice.number("amount")==p.totalAmount()
                && "KRW".equals(p.currency()) && properties.merchantId().equals(p.merchantId())
                && p.paymentKey()!=null && !p.paymentKey().isBlank()
                && attempt.text("payment_key").equalsIgnoreCase(p.paymentKey());
    }
    private void uncertain(String id,long user) {
        locked(user,()->{ ledger.update("update billing_refunds set status='UNKNOWN',lease_until=? where id=?",at(clock.instant().plusSeconds(180)),id); return null; });
    }
}
