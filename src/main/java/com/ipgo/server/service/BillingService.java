package com.ipgo.server.service;

import com.ipgo.server.config.BillingProperties;
import com.ipgo.server.dto.request.BillingRequests;
import com.ipgo.server.dto.response.BillingViews;
import com.ipgo.server.exception.BillingException;
import com.ipgo.server.infrastructure.BillingGateway;
import com.ipgo.server.infrastructure.BillingSecrets;
import com.ipgo.server.repository.BillingLedger;
import com.ipgo.server.repository.BillingLedger.Row;
import com.ipgo.server.util.BillingPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import static com.ipgo.server.repository.BillingLedger.at;

@Service
public class BillingService {
    private final BillingLedger ledger;
    private final BillingProperties properties;
    private final BillingSecrets secrets;
    private final BillingGateway gateway;
    private final Clock clock;
    private final com.ipgo.server.config.NormalPaymentProperties normal;
    public BillingService(BillingLedger l,BillingProperties p,BillingSecrets s,BillingGateway g,Clock c,com.ipgo.server.config.NormalPaymentProperties n) {
        ledger=l; properties=p; secrets=s; gateway=g; clock=c; normal=n;
    }
    public void requireEnabled() {
        if(!properties.enabled()) throw new BillingException(503,"BILLING_DISABLED","결제 기능을 준비 중입니다.");
    }
    public List<BillingViews.Plan> plans() { requireEnabled(); return BillingPolicy.plans().stream().map(p->normal.enabled()?new BillingViews.Plan(p.planId(),p.priceVersion(),p.amount(),p.currency(),"ONE_TIME",p.products(),p.monthlyInsights(),p.testMode(),p.trialDays(),NormalPaymentService.CONSENT):p).toList(); }
    private <T> T locked(long user,Supplier<T> f) {
        requireEnabled(); return ledger.transaction(()->{ ledger.lock(user); ensureNotWithdrawn(user); initialize(user); return f.get(); });
    }
    // #172 탈퇴와 겹쳐 늦게 도착한 재개 · 등록 · 재결제 요청이 탈퇴 뒤 결제를 되살리지 않게 같은 회원 row 잠금 안에서 거절한다.
    private void ensureNotWithdrawn(long user) {
        if(!ledger.list("select id from users where id=? and withdrawn_at is not null",user).isEmpty())
            throw new BillingException(409,"ACCOUNT_WITHDRAWN","탈퇴한 계정이에요.");
    }
    private void initialize(long user) {
        var existing=ledger.list("select merchant_id from billing_subscriptions where user_id=?",user);
        if(!existing.isEmpty()) {
            if(!properties.merchantId().equals(existing.getFirst().text("merchant_id"))) throw BillingException.conflict("BILLING_MERCHANT_CHANGED");
            return;
        }
        Instant created=ledger.one("select created_at from users where id=?",user).time("created_at");
        ledger.update("insert into billing_subscriptions(user_id,merchant_id,mode,status,plan_id,price_version,amount,trial_end,customer_key) values (?, ?,'TEST','TRIALING','trial',?,0,?,?)",
                user,properties.merchantId(),BillingPolicy.VERSION,at(created.plus(Duration.ofDays(14))),uuid());
    }
    private Row sub(long user) { return ledger.one("select * from billing_subscriptions where user_id=?",user); }
    public BillingViews.Subscription subscription(long user) { return locked(user,()->view(sub(user))); }
    private BillingViews.Subscription view(Row s) {
        Instant now=clock.instant(),end=s.time("period_end"),grace=null;
        String status=s.text("status"),reason=null;
        boolean paid=end!=null;
        if("CANCELED".equals(status)) { reason="SUBSCRIPTION_ENDED"; }
        else if(!paid) { if(!now.isBefore(s.time("trial_end"))) { status="TRIAL_EXPIRED"; reason="TRIAL_ENDED"; } }
        else if(!now.isBefore(end)) {
            if(normal.enabled() || s.flag("cancel_at_period_end")) { status="CANCELED"; reason="SUBSCRIPTION_ENDED"; }
            else {
                grace=BillingPolicy.grace(end);
                if(now.isBefore(grace)) status="PAST_DUE";
                else { status="SUSPENDED"; reason="PAYMENT_FAILED"; }
            }
        }
        boolean allowed=reason==null;
        var plan=BillingPolicy.plan(s.text("plan_id"));
        boolean hasMethod=s.text("default_method_id")!=null && !ledger.list("select id from billing_methods where id=? and status='ACTIVE'",s.text("default_method_id")).isEmpty();
        Instant next=normal.enabled() || s.flag("cancel_at_period_end") || !hasMethod?null:s.time("next_charge_at");
        return new BillingViews.Subscription(s.text("plan_id"),status,s.time("trial_end"),s.time("period_start"),end,next,
                (normal.enabled() || s.flag("cancel_at_period_end")),grace,s.number("amount"),s.text("price_version"),
                new BillingViews.Entitlements(allowed,allowed,allowed,plan.products(),plan.monthlyInsights(),reason));
    }
    public void assertWriteAllowed(long user) {
        if(!properties.enabled()) return;
        var e=subscription(user).entitlements();
        if(!e.canCalculate()) throw new BillingException(403,e.reason(),"이용 기간이 끝났습니다. 구독 상태를 확인해주세요.");
    }
    public void assertProductCapacity(long user,long additional) {
        if(!properties.enabled()) return;
        locked(user,()->{
            var e=view(sub(user)).entitlements();
            long count=ledger.one("select count(*) as total from products where user_id=?",user).number("total");
            if(count+additional>e.productLimit()) throw new BillingException(403,"PRODUCT_LIMIT_EXCEEDED","요금제의 상품 수 한도를 초과했습니다.");
            return null;
        });
    }
    private void consent(String version) { if(!BillingPolicy.CONSENT.equals(version)) throw BillingException.conflict("BILLING_CONSENT_REQUIRED"); }
    private String command(long user,String operation,String key,String body) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}")) throw BillingException.invalid();
        var rows=ledger.list("select * from billing_commands where user_id=? and operation=? and command_key=?",user,operation,key);
        if(rows.isEmpty()) return null;
        Row r=rows.getFirst();
        if(!r.text("body_hash").equals(BillingSecrets.hash(body))) throw BillingException.conflict("IDEMPOTENCY_KEY_REUSED");
        return r.text("result_id");
    }
    private void remember(long user,String op,String key,String body,String result) {
        ledger.update("insert into billing_commands(user_id,operation,command_key,body_hash,consent_version,result_id,created_at) values (?,?,?,?,?,?,?)",
                user,op,key,BillingSecrets.hash(body),List.of("REGISTER","RESUME").contains(op)?BillingPolicy.CONSENT:null,result,at(clock.instant()));
    }
    public BillingViews.Registration register(long user,String key,BillingRequests.Registration r) {
        if(normal.enabled()) throw BillingException.conflict("AUTOMATIC_PAYMENT_DISABLED");
        return locked(user,()->{
            String body=r.purpose()+"|"+r.planId()+"|"+r.priceVersion()+"|"+r.consentVersion();
            ensureNoRefund(user);
            String old=command(user,"REGISTER",key,body);
            if(old!=null) return resumableRegistration(user,old);
            consent(r.consentVersion());
            if(!List.of("START_SUBSCRIPTION","REPLACE_METHOD").contains(r.purpose())) throw BillingException.invalid();
            Row s=sub(user); long amount=0;
            if("START_SUBSCRIPTION".equals(r.purpose())) {
                var plan=BillingPolicy.plan(r.planId());
                if(plan.amount()==0) throw BillingException.invalid();
                if(!BillingPolicy.VERSION.equals(r.priceVersion())) throw BillingException.conflict("PLAN_PRICE_CHANGED");
                if(!"CANCELED".equals(s.text("status")) && s.time("period_end")!=null && (!s.flag("cancel_at_period_end") || clock.instant().isBefore(s.time("period_end")))) throw BillingException.conflict("SUBSCRIPTION_ALREADY_ACTIVE");
                if(!ledger.list("select id from billing_invoices where user_id=? and status='OPEN'",user).isEmpty()) throw BillingException.conflict("BILLING_IN_PROGRESS");
                amount=plan.amount();
            } else if(r.planId()!=null || r.priceVersion()!=null) throw BillingException.invalid();
            ledger.update("update billing_sessions set status='UNKNOWN' where user_id=? and status='ISSUING' and expires_at<=?",user,at(clock.instant()));
            ledger.update("update billing_sessions set status='EXPIRED' where user_id=? and status='OPEN' and expires_at<=?",user,at(clock.instant()));
            var active=ledger.list("select * from billing_sessions where user_id=? and status in ('OPEN','ISSUING') and expires_at>?",user,at(clock.instant()));
            if(!active.isEmpty()) {
                Row current=active.getFirst();
                if(!"OPEN".equals(current.text("status")) || !r.purpose().equals(current.text("purpose"))
                        || !java.util.Objects.equals(r.planId(),current.text("plan_id"))
                        || !BillingPolicy.VERSION.equals(current.text("price_version"))
                        || !r.consentVersion().equals(current.text("consent_version")))
                    throw BillingException.conflict("BILLING_IN_PROGRESS");
                remember(user,"REGISTER",key,body,current.text("id"));
                return registration(user,current.text("id"));
            }
            String id=uuid(); Instant now=clock.instant();
            ledger.update("insert into billing_sessions(id,user_id,purpose,plan_id,amount,price_version,consent_version,accepted_at,state_hash,expires_at,status,issue_key) values (?,?,?,?,?,?,?,?,?,?,'OPEN',?)",
                    id,user,r.purpose(),r.planId(),amount,BillingPolicy.VERSION,r.consentVersion(),at(now),BillingSecrets.hash(state(user,id)),at(now.plus(Duration.ofMinutes(15))),uuid());
            remember(user,"REGISTER",key,body,id);
            return registration(user,id);
        });
    }
    private String state(long user,String id) { return secrets.fingerprint("session-state:"+user+":"+id); }
    private BillingViews.Registration resumableRegistration(long user,String id) {
        Row r=owned("billing_sessions",id,user);
        if("CANCELED".equals(r.text("status"))) throw BillingException.conflict("BILLING_SESSION_CANCELED");
        if(!clock.instant().isBefore(r.time("expires_at"))) throw BillingException.conflict("BILLING_SESSION_EXPIRED");
        if(!"OPEN".equals(r.text("status"))) throw BillingException.conflict("BILLING_IN_PROGRESS");
        return registration(user,id);
    }
    /** 카드 발급 시작 전의 등록 시도만 닫는다. complete와 같은 사용자 lock으로 직렬화한다. */
    public BillingViews.Session cancelRegistration(long user,String id,String key) {
        return locked(user,()->{
            Row r=owned("billing_sessions",id,user);
            String operation="CANCEL_REGISTRATION:"+id;
            String old=command(user,operation,key,id);
            if(old!=null) return session(user,id);
            String status=r.text("status");
            if("OPEN".equals(status)) {
                status=clock.instant().isBefore(r.time("expires_at"))?"CANCELED":"EXPIRED";
                ledger.update("update billing_sessions set status=? where id=?",status,id);
            } else if(!"CANCELED".equals(status) && !"EXPIRED".equals(status)) {
                throw BillingException.conflict("BILLING_IN_PROGRESS");
            }
            remember(user,operation,key,id,id);
            return session(user,id);
        });
    }
    private BillingViews.Registration registration(long user,String id) {
        Row r=owned("billing_sessions",id,user);
        String query="?sessionId="+id+"&state="+state(user,id);
        return new BillingViews.Registration(id,sub(user).text("customer_key"),state(user,id),r.time("expires_at"),
                properties.returnOrigin()+"/billing/auth/success"+query,properties.returnOrigin()+"/billing/auth/fail"+query);
    }
    public BillingViews.Session complete(long user,String id,String key,BillingRequests.Complete r) {
        if(normal.enabled()) throw BillingException.conflict("AUTOMATIC_PAYMENT_DISABLED");
        String body=BillingSecrets.hash(r.authKey())+"|"+r.customerKey()+"|"+r.state();
        Row session=locked(user,()->{
            Row t=owned("billing_sessions",id,user);
            String old=command(user,"COMPLETE:"+id,key,body);
            if(!sub(user).text("customer_key").equals(r.customerKey()) || !t.text("state_hash").equals(BillingSecrets.hash(r.state())))
                throw BillingException.forbidden();
            if(old!=null || "COMPLETED".equals(t.text("status"))) return null;
            if("CANCELED".equals(t.text("status"))) throw BillingException.conflict("BILLING_SESSION_CANCELED");
            if(!clock.instant().isBefore(t.time("expires_at"))) throw BillingException.conflict("BILLING_SESSION_EXPIRED");
            if(!"OPEN".equals(t.text("status"))) throw BillingException.conflict("BILLING_IN_PROGRESS");
            ledger.update("update billing_sessions set status='ISSUING',auth_hash=? where id=?",BillingSecrets.hash(r.authKey()),id);
            remember(user,"COMPLETE:"+id,key,body,id);
            return t;
        });
        if(session==null) return session(user,id);
        BillingGateway.Issued issued;
        try { issued=gateway.issue(r.authKey(),r.customerKey(),session.text("issue_key")); }
        catch(BillingGateway.Failure e) {
            locked(user,()->{ ledger.update("update billing_sessions set status=? where id=? and status='ISSUING'",e.rejected()?"REJECTED":"UNKNOWN",id); return null; });
            return session(user,id);
        }
        if(!r.customerKey().equals(issued.customerKey()) || issued.billingKey()==null || issued.billingKey().isBlank()
                || issued.maskedNumber()==null || !issued.maskedNumber().contains("*")) {
            locked(user,()->{ ledger.update("update billing_sessions set status='UNKNOWN' where id=? and status='ISSUING'",id); return null; });
            return session(user,id);
        }
        locked(user,()->{
            Row current=sub(user);
            Row finishing=owned("billing_sessions",id,user);
            boolean issuing="ISSUING".equals(finishing.text("status"));
            boolean valid=issuing && clock.instant().isBefore(finishing.time("expires_at"));
            String method=uuid(),fingerprint=secrets.fingerprint(issued.billingKey());
            ledger.lockFingerprint(fingerprint);
            boolean deleted=!ledger.list("select event_key from billing_webhook_inbox where event_type='BILLING_DELETED' and entity_fingerprint=?",fingerprint).isEmpty();
            String methodStatus=deleted?"DELETED":valid?"ACTIVE":"RETIRED";
            ledger.update("insert into billing_methods(id,user_id,encrypted_key,fingerprint,masked_number,status,created_at) values (?,?,?,?,?,?,?)",
                    method,user,secrets.encrypt(issued.billingKey()),fingerprint,issued.maskedNumber(),methodStatus,at(clock.instant()));
            if(!valid || deleted) {
                if(issuing) ledger.update("update billing_sessions set status=? where id=?",deleted?"REJECTED":"EXPIRED",id);
                return null;
            }
            // 다른 요청이 만든 세션은 OPEN/ISSUING 동안 차단되므로 수단 교체는 한 번만 발생한다.
            if(current.text("default_method_id")!=null)
                ledger.update("update billing_methods set status='RETIRED' where id=? and status='ACTIVE'",current.text("default_method_id"));
            ledger.update("update billing_subscriptions set default_method_id=? where user_id=?",method,user);
            ledger.update("update billing_sessions set status='COMPLETED',method_id=? where id=?",method,id);
            if("START_SUBSCRIPTION".equals(session.text("purpose"))) {
                Instant start=clock.instant();
                int cycle=nextCycle(user);
                String invoice=invoice(user,id,cycle,session.text("plan_id"),session.number("amount"),session.text("price_version"),start,BillingPolicy.boundary(start,1));
                attempt(user,invoice,method);
            }
            return null;
        });
        return session(user,id);
    }
    public BillingViews.Session session(long user,String id) {
        return locked(user,()->{
            Row r=owned("billing_sessions",id,user);
            var invoices=ledger.list("select id from billing_invoices where session_id=?",id);
            String invoice=invoices.isEmpty()?null:invoices.getFirst().text("id");
            Row a=invoice==null?null:lastAttempt(invoice);
            String status=r.text("status");
            if(!clock.instant().isBefore(r.time("expires_at"))) {
                if("ISSUING".equals(status)) status="UNKNOWN";
                if("OPEN".equals(status)) status="EXPIRED";
            }
            return new BillingViews.Session(id,status,invoice,a==null?null:a.text("status"),a==null?null:a.text("error_code"));
        });
    }
    public List<BillingViews.Method> methods(long user) {
        return locked(user,()->{
            String current=sub(user).text("default_method_id");
            return ledger.list("select * from billing_methods where user_id=? order by created_at desc",user).stream()
                    .map(r->new BillingViews.Method(r.text("id"),r.text("masked_number"),r.text("id").equals(current),r.text("status"))).toList();
        });
    }
    public BillingViews.InvoicePage invoices(long user,String cursor) {
        return locked(user,()->{
            int before=Integer.MAX_VALUE;
            if(cursor!=null) { try { before=Integer.parseInt(cursor); if(before<1) throw BillingException.invalid(); } catch(NumberFormatException e) { throw BillingException.invalid(); } }
            var rows=ledger.list("select * from billing_invoices where user_id=? and cycle_no<? order by cycle_no desc limit 21",user,before);
            var items=rows.stream().limit(20).map(r->{ Row a=lastAttempt(r.text("id"));
                Row n=normal.enabled()?ledger.list("select * from billing_normal_orders where invoice_id=?",r.text("id")).stream().findFirst().orElse(null):null;
                String payment=n==null?(a==null?null:a.text("status")):switch(n.text("status")) { case "PAID","CANCELED" -> "SUCCEEDED"; case "PENDING" -> "WAITING_FOR_DEPOSIT"; case "FAILED" -> "DECLINED"; default -> n.text("status"); };
                return new BillingViews.Invoice(r.text("id"),r.text("plan_id"),r.number("amount"),"KRW",r.time("period_start"),r.time("period_end"),r.text("status"),payment,r.time("paid_at"),
                    n!=null?n.text("refund_status"):ledger.list("select status from billing_refunds where invoice_id=?",r.text("id")).stream().findFirst().map(x->x.text("status")).orElse(null),
                    n!=null?n.time("canceled_at"):ledger.list("select canceled_at from billing_refunds where invoice_id=?",r.text("id")).stream().findFirst().map(x->x.time("canceled_at")).orElse(null)); }).toList();
            return new BillingViews.InvoicePage(items,rows.size()>20?Long.toString(rows.get(19).number("cycle_no")):null);
        });
    }
    public BillingViews.Subscription cancel(long user,String key) {
        if(normal.enabled()) throw BillingException.conflict("AUTOMATIC_PAYMENT_DISABLED");
        return locked(user,()->{
            if(command(user,"CANCEL",key,"")!=null) return view(sub(user));
            ensureNoFlight(user);
            stopCharges(user);
            remember(user,"CANCEL",key,"",uuid()); return view(sub(user));
        });
    }
    /**
     * #172 회원 탈퇴. 탈퇴 transaction 안에서 회원 row를 잠근 채 개인정보 지우기와 함께 처리한다(같은 잠금이라 재개 · 결제와 섞이지 않는다).
     * 멱등 키로 건너뛰지 않고 매번 결제 중인지 확인해 409 BILLING_IN_PROGRESS로 막는다. 결제 기능이 꺼져 있어도 예전 구독은 멈춘다.
     */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void stopForWithdrawal(long user) {
        ledger.lock(user);
        if(ledger.list("select user_id from billing_subscriptions where user_id=?",user).isEmpty()) return;
        ensureNoFlight(user);
        stopCharges(user);
    }
    private void stopCharges(long user) {
        ledger.update("update billing_sessions set status='CANCELED' where user_id=? and purpose='START_SUBSCRIPTION' and status in ('OPEN','ISSUING')",user);
        ledger.update("update billing_subscriptions set cancel_at_period_end=true,next_charge_at=null where user_id=?",user);
        ledger.update("update billing_attempts set status='VOID' where user_id=? and status in ('READY','DECLINED')",user);
        ledger.update("update billing_invoices set status='VOID' where user_id=? and status='OPEN'",user);
    }
    public BillingViews.Subscription resume(long user,String key,String consentVersion) {
        if(normal.enabled()) throw BillingException.conflict("AUTOMATIC_PAYMENT_DISABLED");
        return locked(user,()->{
            if(command(user,"RESUME",key,consentVersion)!=null) return view(sub(user));
            consent(consentVersion); ensureNoFlight(user); Row s=sub(user);
            if("CANCELED".equals(s.text("status")) || !s.flag("cancel_at_period_end") || s.time("period_end")==null || !clock.instant().isBefore(s.time("period_end"))) throw BillingException.conflict("SUBSCRIPTION_NOT_RESUMABLE");
            activeMethod(s);
            ledger.update("update billing_subscriptions set cancel_at_period_end=false,next_charge_at=period_end where user_id=?",user);
            remember(user,"RESUME",key,consentVersion,uuid()); return view(sub(user));
        });
    }
    public BillingViews.Accepted retry(long user,String invoice,String key) {
        if(normal.enabled()) throw BillingException.conflict("AUTOMATIC_PAYMENT_DISABLED");
        return locked(user,()->{
            Row i=owned("billing_invoices",invoice,user);
            if(command(user,"RETRY:"+invoice,key,"")!=null) return new BillingViews.Accepted(invoice,i.text("status"));
            Row a=lastAttempt(invoice); Row s=sub(user);
            if(!"OPEN".equals(i.text("status")) || a==null || !"DECLINED".equals(a.text("status")) || (s.flag("cancel_at_period_end") && i.text("session_id")==null)
                    || !clock.instant().isBefore(i.time("period_end"))) throw BillingException.conflict("BILLING_IN_PROGRESS");
            ensureNoFlight(user); String method=activeMethod(s); attempt(user,invoice,method);
            remember(user,"RETRY:"+invoice,key,"",invoice); return new BillingViews.Accepted(invoice,"OPEN");
        });
    }
    private void ensureNoRefund(long user) {
        if(!ledger.list("select id from billing_refunds where user_id=? and status<>'CANCELED'",user).isEmpty())
            throw BillingException.conflict("REFUND_IN_PROGRESS");
    }
    private void ensureNoFlight(long user) {
        if(normal.enabled() && !ledger.list("select id from billing_normal_orders where user_id=? and (status in ('CONFIRMING','UNKNOWN','PENDING','REVIEW_REQUIRED') or refund_status in ('PROCESSING','UNKNOWN','REVIEW_REQUIRED'))",user).isEmpty()) throw BillingException.conflict("BILLING_IN_PROGRESS");
        ensureNoRefund(user);
        if(!ledger.list("select id from billing_attempts where user_id=? and status in ('DISPATCHING','UNKNOWN','REVIEW_REQUIRED')",user).isEmpty()) throw BillingException.conflict("BILLING_IN_PROGRESS");
    }
    private String activeMethod(Row s) {
        String method=s.text("default_method_id");
        if(method==null || ledger.list("select id from billing_methods where id=? and status='ACTIVE'",method).isEmpty()) throw BillingException.conflict("BILLING_METHOD_REQUIRED");
        return method;
    }
    private Row owned(String table,String id,long user) {
        // table은 이 클래스의 고정 리터럴만 전달한다.
        Row row=ledger.one("select * from "+table+" where id=?",id);
        if(row.number("user_id")!=user) throw BillingException.forbidden(); return row;
    }
    private Row lastAttempt(String invoice) { var rows=ledger.list("select * from billing_attempts where invoice_id=? order by attempt_no desc limit 1",invoice); return rows.isEmpty()?null:rows.getFirst(); }
    private int nextCycle(long user) { return (int)ledger.one("select coalesce(max(cycle_no),0)+1 as n from billing_invoices where user_id=?",user).number("n"); }
    private String invoice(long user,String session,int cycle,String plan,long amount,String version,Instant start,Instant end) {
        String id=uuid(); ledger.update("insert into billing_invoices(id,user_id,session_id,cycle_no,plan_id,price_version,amount,order_name,currency,period_start,period_end,status) values (?,?,?,?,?,?,?,?,'KRW',?,?,'OPEN')",
                id,user,session,cycle,plan,version,amount,"Ipgo "+plan+" monthly",at(start),at(end)); return id;
    }
    private void attempt(long user,String invoice,String method) {
        int n=(int)ledger.one("select count(*)+1 as n from billing_attempts where invoice_id=?",invoice).number("n");
        String id=uuid(),order="ipgo_"+uuid();
        Row invoiceRow=ledger.one("select * from billing_invoices where id=?",invoice);
        String bodyHash=chargeHash(order,sub(user).text("customer_key"),invoiceRow);
        ledger.update("insert into billing_attempts(id,invoice_id,user_id,method_id,attempt_no,order_id,idempotency_key,body_hash,status,created_at) values (?,?,?,?,?,?,?,?,'READY',?)",
                id,invoice,user,method,n,order,uuid(),bodyHash,at(clock.instant()));
    }
    private String chargeHash(String order,String customer,Row invoice) {
        return BillingSecrets.hash(order+"|"+customer+"|"+invoice.number("amount")+"|"+invoice.text("order_name"));
    }
    public static String uuid() { return UUID.randomUUID().toString(); }
    /** 내부 작업기 진입점. 공개 즉시 청구 API는 제공하지 않는다. */
    public void processOne() {
        if(normal.enabled()) return;
        requireEnabled();
        var rows=ledger.list("select id,user_id from billing_attempts where status='READY' or (status in ('DISPATCHING','UNKNOWN') and (lease_until is null or lease_until<=?)) order by created_at limit 1",at(clock.instant()));
        if(rows.isEmpty()) return;
        String id=rows.getFirst().text("id"); long user=rows.getFirst().number("user_id");
        Row a=locked(user,()->{
            Row fresh=owned("billing_attempts",id,user);
            if("REVIEW_REQUIRED".equals(fresh.text("status"))) return null;
            if("SUCCEEDED".equals(fresh.text("status")) || "DECLINED".equals(fresh.text("status")) || "VOID".equals(fresh.text("status"))) return null;
            if(!"READY".equals(fresh.text("status")) && !clock.instant().isBefore(fresh.time("created_at").plus(Duration.ofDays(15)))) {
                ledger.update("update billing_attempts set status='REVIEW_REQUIRED',error_code='PAYMENT_REVIEW_REQUIRED',lease_until=null where id=?",id);
                return null;
            }
            if(fresh.time("lease_until")!=null && clock.instant().isBefore(fresh.time("lease_until"))) return null;
            Row i=owned("billing_invoices",fresh.text("invoice_id"),user); Row s=sub(user);
            if("READY".equals(fresh.text("status")) && ((s.flag("cancel_at_period_end") && i.text("session_id")==null) || !"OPEN".equals(i.text("status"))
                    || !clock.instant().isBefore(i.time("period_end")))) {
                ledger.update("update billing_attempts set status='VOID' where id=?",id); return null;
            }
            boolean initial="READY".equals(fresh.text("status"));
            if(initial) {
                if(i.text("session_id")!=null && !clock.instant().isBefore(fresh.time("created_at").plus(Duration.ofMinutes(15)))) {
                    ledger.update("update billing_attempts set status='VOID',error_code='CHECKOUT_EXPIRED' where id=?",id);
                    ledger.update("update billing_invoices set status='VOID' where id=?",i.text("id"));
                    return null;
                }
                var active=ledger.list("select id from billing_methods where id=? and user_id=? and status='ACTIVE'",s.text("default_method_id"),user);
                if(active.isEmpty()) {
                    ledger.update("update billing_attempts set status='DECLINED',error_code='BILLING_METHOD_REQUIRED' where id=?",id);
                    return null;
                }
                // 아직 PG를 호출하지 않은 READY만 새 기본 수단에 결속한다. 진행/미확정 시도는 그대로 보존한다.
                if(!active.getFirst().text("id").equals(fresh.text("method_id"))) {
                    ledger.update("update billing_attempts set method_id=? where id=?",active.getFirst().text("id"),id);
                    fresh=owned("billing_attempts",id,user);
                }
            }
            ledger.update("update billing_attempts set status=?,lease_until=?,last_checked_at=? where id=?",
                    initial?"DISPATCHING":"UNKNOWN",at(clock.instant().plusSeconds(180)),at(clock.instant()),id);
            return fresh;
        });
        if(a==null) return;
        Row i=ledger.one("select * from billing_invoices where id=?",a.text("invoice_id"));
        try {
            BillingGateway.Payment payment;
            if("READY".equals(a.text("status"))) {
                if(!a.text("body_hash").equals(chargeHash(a.text("order_id"),sub(user).text("customer_key"),i))) {
                    outcome(user,id,false,"PAYMENT_REQUEST_CHANGED"); return;
                }
                Row m=ledger.one("select * from billing_methods where id=?",a.text("method_id"));
                if("DELETED".equals(m.text("status"))) { outcome(user,id,true,"BILLING_METHOD_REQUIRED"); return; }
                payment=gateway.charge(secrets.decrypt(m.text("encrypted_key")),new BillingGateway.Charge(a.text("order_id"),sub(user).text("customer_key"),i.number("amount"),i.text("order_name")),a.text("idempotency_key"));
            } else payment=gateway.lookup(a.text("order_id"));
            boolean matches=a.text("order_id").equals(payment.orderId()) && i.number("amount")==payment.totalAmount()
                    && "KRW".equals(payment.currency()) && properties.merchantId().equals(payment.merchantId())
                    && payment.paymentKey()!=null && !payment.paymentKey().isBlank() && payment.paymentKey().length()<=200;
            if(matches && "ABORTED".equals(payment.status()) && payment.approvedAt()==null) {
                outcome(user,id,true,"PAYMENT_REJECTED"); return;
            }
            if(!matches || !"DONE".equals(payment.status()) || payment.approvedAt()==null || payment.approvedAt().isAfter(clock.instant().plusSeconds(60))
                    || payment.approvedAt().isBefore(a.time("created_at").minusSeconds(60))) {
                outcome(user,id,false,"PAYMENT_OUTCOME_UNKNOWN"); return;
            }
            locked(user,()->{
                Row current=owned("billing_invoices",i.text("id"),user);
                if("PAID".equals(current.text("status"))) return null;
                ledger.update("update billing_attempts set status='SUCCEEDED',payment_key=?,error_code=null,lease_until=null where id=?",payment.paymentKey(),id);
                boolean first=i.text("session_id")!=null;
                Instant start=first?payment.approvedAt():i.time("period_start");
                Instant end=first?BillingPolicy.boundary(start,1):i.time("period_end");
                ledger.update("update billing_invoices set status='PAID',paid_at=?,period_start=?,period_end=? where id=?",at(payment.approvedAt()),at(start),at(end),i.text("id"));
                Instant anchor=first?start:sub(user).time("anchor_at");
                ledger.update("update billing_subscriptions set status='ACTIVE',plan_id=?,price_version=?,amount=?,period_start=?,period_end=?,anchor_at=?,cycle_no=?,next_charge_at=?,cancel_at_period_end=? where user_id=?",
                        i.text("plan_id"),i.text("price_version"),i.number("amount"),at(start),at(end),at(anchor),i.number("cycle_no"),at(end),first?false:sub(user).flag("cancel_at_period_end"),user);
                return null;
            });
        } catch(BillingGateway.Failure e) { outcome(user,id,"READY".equals(a.text("status")) && e.rejected(),e.rejected()?"PAYMENT_REJECTED":"PAYMENT_OUTCOME_UNKNOWN"); }
        // DB 반영 실패는 DISPATCHING lease를 남겨 조회로 복구한다. 새 승인으로 우회하지 않는다.
    }
    private void outcome(long user,String id,boolean rejected,String code) {
        locked(user,()->{ ledger.update("update billing_attempts set status=?,error_code=?,lease_until=? where id=? and status in ('DISPATCHING','UNKNOWN')",
                rejected?"DECLINED":"UNKNOWN",code,rejected?null:at(clock.instant().plusSeconds(180)),id); return null; });
    }
    public void prepareRenewals() {
        if(normal.enabled()) return;
        requireEnabled();
        var due=ledger.list("select user_id from billing_subscriptions s where status='ACTIVE' and cancel_at_period_end=false and next_charge_at<=? and next_charge_at>? and not exists (select 1 from billing_invoices i where i.user_id=s.user_id and i.status='OPEN') and exists (select 1 from billing_methods m where m.id=s.default_method_id and m.status='ACTIVE') order by next_charge_at limit 50",
                at(clock.instant()),at(clock.instant().atZone(BillingPolicy.SEOUL).minusMonths(1).toInstant()));
        for(Row row:due) locked(row.number("user_id"),()->{
            long user=row.number("user_id"); Row s=sub(user);
            if(s.flag("cancel_at_period_end") || s.time("next_charge_at")==null || clock.instant().isBefore(s.time("next_charge_at"))) return null;
            if(!ledger.list("select id from billing_invoices where user_id=? and status='OPEN'",user).isEmpty()) return null;
            if(s.text("default_method_id")==null || ledger.list("select id from billing_methods where id=? and status='ACTIVE'",s.text("default_method_id")).isEmpty()) return null;
            Instant start=s.time("period_end"),anchor=s.time("anchor_at");
            int months=(int)java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(anchor.atZone(BillingPolicy.SEOUL)),java.time.YearMonth.from(start.atZone(BillingPolicy.SEOUL)))+1;
            Instant end=BillingPolicy.boundary(anchor,months);
            if(!clock.instant().isBefore(end)) return null; // 여러 달 체납분 몰아 청구 금지
            // 동일 기간의 VOID 청구가 이미 있으면 해지 후 resume의 다음 갱신을 새로 만들지 않는다.
            var existing=ledger.list("select id,status from billing_invoices where user_id=? and period_start=? and session_id is null",user,at(start));
            if(!existing.isEmpty()) {
                Row previous=existing.getFirst();
                if("VOID".equals(previous.text("status"))) {
                    ledger.update("update billing_invoices set status='OPEN' where id=?",previous.text("id"));
                    attempt(user,previous.text("id"),activeMethod(s));
                }
                return null;
            }
            String invoice=invoice(user,null,nextCycle(user),s.text("plan_id"),s.number("amount"),s.text("price_version"),start,end);
            attempt(user,invoice,activeMethod(s)); return null;
        });
    }
    public void retireOneMethod() {
        requireEnabled();
        var rows=ledger.list("select id,user_id from billing_methods m where status='RETIRED' and not exists (select 1 from billing_attempts a where a.method_id=m.id and a.status in ('READY','DISPATCHING','UNKNOWN','REVIEW_REQUIRED')) order by created_at limit 1");
        if(rows.isEmpty()) return;
        Row row=rows.getFirst();
        // 삭제 후보를 사용자 lock 안에서 재확인한다. RETIRED 수단은 새 시도에 선택되지 않는다.
        Row method=locked(row.number("user_id"),()->{
            Row m=owned("billing_methods",row.text("id"),row.number("user_id"));
            if(!"RETIRED".equals(m.text("status")) || !ledger.list("select id from billing_attempts where method_id=? and status in ('READY','DISPATCHING','UNKNOWN','REVIEW_REQUIRED')",m.text("id")).isEmpty()) return null;
            return m;
        });
        if(method==null) return;
        gateway.delete(secrets.decrypt(method.text("encrypted_key")));
        locked(row.number("user_id"),()->{ ledger.update("update billing_methods set status='DELETED' where id=? and status='RETIRED'",row.text("id")); return null; });
    }
    public void deletedWebhook(String billingKey,String eventKey) {
        String fp=secrets.fingerprint(billingKey);
        ledger.transaction(()->{
            // 원본 key/본문은 저장하지 않는다. row lock은 실제 소유자를 찾아 획득한다.
            var methods=ledger.list("select user_id from billing_methods where fingerprint=?",fp);
            if(!methods.isEmpty()) ledger.lock(methods.getFirst().number("user_id"));
            ledger.lockFingerprint(fp);
            if(ledger.list("select event_key from billing_webhook_inbox where event_key=?",eventKey).isEmpty())
                ledger.update("insert into billing_webhook_inbox(event_key,event_type,entity_fingerprint,received_at) values (?,'BILLING_DELETED',?,?)",eventKey,fp,at(clock.instant()));
            ledger.update("update billing_methods set status='DELETED' where fingerprint=?",fp);
            return null;
        });
    }
}
