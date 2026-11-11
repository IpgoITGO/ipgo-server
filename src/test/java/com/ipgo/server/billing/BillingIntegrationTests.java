package com.ipgo.server.billing;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.request.BillingRequests;
import com.ipgo.server.dto.response.BillingViews;
import com.ipgo.server.exception.BillingException;
import com.ipgo.server.infrastructure.BillingGateway;
import com.ipgo.server.infrastructure.BillingSecrets;
import com.ipgo.server.service.BillingService;
import com.ipgo.server.util.BillingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BillingIntegrationTests extends DatabaseIntegrationTest {
    @Autowired BillingService service;
    @Autowired com.ipgo.server.service.BillingRefundService refunds;
    @Autowired BillingSecrets secrets;
    @Autowired com.ipgo.server.service.EngineCalculationService calculations;
    @Autowired com.ipgo.server.service.WarehouseService warehouses;
    @MockitoBean com.ipgo.server.service.EngineJobs jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @MockitoBean BillingGateway gateway;
    @MockitoBean Clock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.ipgo.server.repository.BillingLedger ledger;
    static final Instant START=Instant.parse("2026-01-31T01:00:00Z");
    static final String WEBHOOK_KEY="local-webhook-fixture-only";
    static final String STORAGE_KEY=Base64.getEncoder().encodeToString(new byte[32]);
    static final String LOOKUP_KEY;
    static { byte[] b=new byte[32]; java.util.Arrays.fill(b,(byte)1); LOOKUP_KEY=Base64.getEncoder().encodeToString(b); }
    Instant now;
    long user,other;
    @DynamicPropertySource static void billingProperties(DynamicPropertyRegistry r) {
        r.add("ipgo.billing.enabled",()->"true");
        r.add("ipgo.billing.worker-enabled",()->"false");
        r.add("ipgo.billing.secret-key",()->"test_sk_local_fixture_not_a_credential");
        r.add("ipgo.billing.security-key",()->WEBHOOK_KEY);
        r.add("ipgo.billing.encryption-key",()->STORAGE_KEY);
        r.add("ipgo.billing.lookup-key",()->LOOKUP_KEY);
        r.add("ipgo.billing.merchant-id",()->"test-merchant");
        r.add("ipgo.engine.enabled",()->"false");
    }
    @BeforeEach void setup() throws Exception {
        String sql=new String(new org.springframework.core.io.ClassPathResource("db/migration/V17__create_billing_ledger.sql").getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        // PostgreSQL 검증 실행은 migration 전체를 외부에서 먼저 적용한다.
        boolean pg;
        try(var connection=jdbc.getDataSource().getConnection()) { pg=connection.getMetaData().getDatabaseProductName().contains("PostgreSQL"); }
        if(!pg && jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name)='billing_subscriptions'",Integer.class)==0) {
            String portable=sql.split("-- PostgreSQL 전용")[0];
            for(String statement:portable.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        }
        if(!pg && jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name)='billing_refunds'",Integer.class)==0) {
            String refunds=new String(new org.springframework.core.io.ClassPathResource("db/migration/V24__create_billing_refunds.sql").getInputStream().readAllBytes(),StandardCharsets.UTF_8).split("-- PostgreSQL 전용")[0];
            for(String statement:refunds.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        }
        clearLedger(pg);
        now=START; when(clock.instant()).thenAnswer(i->now);
        when(clock.withZone(any(java.time.ZoneId.class))).thenAnswer(i->Clock.fixed(now,i.getArgument(0)));
        user=newUser(); other=newUser();
        when(gateway.issue(anyString(),anyString(),anyString())).thenAnswer(i->new BillingGateway.Issued("fixture-key-"+UUID.randomUUID(),i.getArgument(1),"1234****5678"));
        when(gateway.charge(anyString(),any(),anyString())).thenAnswer(i->{ var c=i.getArgument(1,BillingGateway.Charge.class); return new BillingGateway.Payment("payment-"+UUID.randomUUID(),c.orderId(),c.amount(),"KRW","DONE","test-merchant",now); });
    }
    @org.junit.jupiter.api.AfterEach void cleanupBilling() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            clearLedger(connection.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
        }
        jdbc.update("delete from warehouses where user_id in (?,?)",user,other);
    }
    private void clearLedger(boolean pg) throws Exception {
        String[] tables={"billing_refunds","billing_webhook_inbox","billing_commands","billing_attempts","billing_invoices","billing_sessions","billing_methods","billing_subscriptions"};
        if(pg) {
            String url=System.getenv("IPGO_TEST_DB_URL");
            if(!"temporary".equals(System.getenv("IPGO_BILLING_TEST_DATABASE")) || !url.endsWith("/ipgo_billing_test"))
                throw new IllegalStateException("Billing tests require an isolated temporary database");
            // 실제 API 연결은 ipgo_server. 정리만 폐기용 DB의 소유자 역할로 수행한다.
            try(var admin=java.sql.DriverManager.getConnection(url,"ipgo_migrator",""); var statement=admin.createStatement()) {
                statement.executeUpdate("update billing_subscriptions set default_method_id=null");
                for(String table:tables) statement.executeUpdate("delete from "+table);
            }
        } else { jdbc.update("update billing_subscriptions set default_method_id=null"); for(String table:tables) jdbc.update("delete from "+table); }
    }
    long newUser() {
        jdbc.update("insert into users(email,password_hash,created_at) values (?,'fixture',?)",UUID.randomUUID()+"@example.test",java.sql.Timestamp.from(START.minusSeconds(3600)));
        return jdbc.queryForObject("select max(id) from users",Long.class);
    }
    BillingViews.Registration registration() { return service.register(user,UUID.randomUUID().toString(),new BillingRequests.Registration("START_SUBSCRIPTION","seller",BillingPolicy.VERSION,BillingPolicy.CONSENT)); }
    BillingViews.Session complete(BillingViews.Registration r) { return service.complete(user,r.sessionId(),UUID.randomUUID().toString(),new BillingRequests.Complete("fixture-auth",r.customerKey(),r.state())); }
    BillingViews.Session checkout() { return complete(registration()); }
    void activate() { checkout(); service.processOne(); }
    String invoice() { return service.invoices(user,null).items().getFirst().invoiceId(); }
    @Test void trialUsesSignupAndCannotBeResetByReading() {
        var a=service.subscription(user);
        assertThat(a.trialEnd()).isEqualTo(START.minusSeconds(3600).plus(Duration.ofDays(14)));
        now=a.trialEnd(); assertThat(service.subscription(user).entitlements().reason()).isEqualTo("TRIAL_ENDED");
        assertThatThrownBy(()->service.assertWriteAllowed(user)).isInstanceOf(BillingException.class);
        assertThat(service.subscription(user).trialEnd()).isEqualTo(a.trialEnd());
    }
    @Test void registrationIsIdempotentAndPriceAndConsentAreServerControlled() {
        String key=UUID.randomUUID().toString();
        var request=new BillingRequests.Registration("START_SUBSCRIPTION","seller",BillingPolicy.VERSION,BillingPolicy.CONSENT);
        var first=service.register(user,key,request);
        assertThat(service.register(user,key,request)).isEqualTo(first);
        assertThatThrownBy(()->service.register(user,key,new BillingRequests.Registration("START_SUBSCRIPTION","business",BillingPolicy.VERSION,BillingPolicy.CONSENT))).isInstanceOf(BillingException.class);
        assertThatThrownBy(()->service.register(other,key,new BillingRequests.Registration("START_SUBSCRIPTION","seller","old",BillingPolicy.CONSENT))).isInstanceOf(BillingException.class);
        verifyNoInteractions(gateway);
    }
    @Test void cardRegistrationDoesNotActivateUntilApprovalAndStoresOnlyCiphertext() {
        var r=registration(); var result=complete(r);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(service.subscription(user).planId()).isEqualTo("trial");
        assertThat(result.paymentStatus()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select encrypted_key from billing_methods",String.class)).startsWith("v1.").doesNotContain("fixture-key");
        service.processOne();
        var sub=service.subscription(user);
        assertThat(sub.status()).isEqualTo("ACTIVE"); assertThat(sub.amount()).isEqualTo(500);
        assertThat(sub.periodEnd()).isEqualTo(Instant.parse("2026-02-28T01:00:00Z"));
        assertThat(service.invoices(user,null).items().getFirst().status()).isEqualTo("PAID");
        service.processOne(); verify(gateway,times(1)).charge(anyString(),any(),anyString());
    }
    @Test void duplicateCompletionNeverIssuesOrChargesTwice() {
        var r=registration(); String key=UUID.randomUUID().toString();
        var body=new BillingRequests.Complete("fixture-auth",r.customerKey(),r.state());
        service.complete(user,r.sessionId(),key,body); service.complete(user,r.sessionId(),key,body);
        service.complete(user,r.sessionId(),UUID.randomUUID().toString(),body);
        assertThat(jdbc.queryForObject("select count(*) from billing_invoices",Integer.class)).isEqualTo(1);
        verify(gateway,times(1)).issue(anyString(),anyString(),anyString());
    }
    @Test void ownershipStateCustomerAndExpiryAreCheckedBeforeProvider() {
        var r=registration();
        assertThatThrownBy(()->service.session(other,r.sessionId())).isInstanceOf(BillingException.class);
        assertThatThrownBy(()->service.complete(user,r.sessionId(),UUID.randomUUID().toString(),new BillingRequests.Complete("a",r.customerKey(),"wrong"))).isInstanceOf(BillingException.class);
        assertThatThrownBy(()->service.complete(user,r.sessionId(),UUID.randomUUID().toString(),new BillingRequests.Complete("a","wrong",r.state()))).isInstanceOf(BillingException.class);
        now=r.expiresAt(); assertThatThrownBy(()->complete(r)).isInstanceOf(BillingException.class);
        verifyNoInteractions(gateway);
    }
    @Test void issueTimeoutDoesNotCreateChargeAndCanBeInspected() {
        when(gateway.issue(anyString(),anyString(),anyString())).thenThrow(new BillingGateway.Failure(false,"timeout"));
        var s=checkout(); assertThat(s.status()).isEqualTo("UNKNOWN");
        assertThat(s.invoiceId()).isNull(); verify(gateway,never()).charge(anyString(),any(),anyString());
    }
    @Test void approvalTimeoutRecoversByLookupWithNoNewCharge() {
        checkout(); doThrow(new BillingGateway.Failure(false,"timeout")).when(gateway).charge(anyString(),any(),anyString());
        service.processOne();
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->service.retry(user,invoice(),UUID.randomUUID().toString())).isInstanceOf(BillingException.class);
        assertThatThrownBy(()->service.cancel(user,UUID.randomUUID().toString())).isInstanceOf(BillingException.class);
        String order=jdbc.queryForObject("select order_id from billing_attempts",String.class);
        when(gateway.lookup(order)).thenReturn(new BillingGateway.Payment("recovered",order,500,"KRW","DONE","test-merchant",now));
        now=now.plusSeconds(181); service.processOne();
        assertThat(service.subscription(user).planId()).isEqualTo("seller");
        verify(gateway,times(1)).charge(anyString(),any(),anyString()); verify(gateway).lookup(order);
    }
    @Test void mismatchedAmountNeverGrantsPaidAccess() {
        checkout(); doAnswer(i->{var c=i.getArgument(1,BillingGateway.Charge.class); return new BillingGateway.Payment("x",c.orderId(),1,"KRW","DONE","test-merchant",now);}).when(gateway).charge(anyString(),any(),anyString());
        service.processOne(); assertThat(service.subscription(user).planId()).isEqualTo("trial");
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("UNKNOWN");
    }
    @Test void explicitDeclineCanRetrySameInvoiceOnlyOncePerCommand() {
        checkout(); doThrow(new BillingGateway.Failure(true,"declined")).when(gateway).charge(anyString(),any(),anyString());
        service.processOne(); String invoice=invoice(),key=UUID.randomUUID().toString();
        service.retry(user,invoice,key); service.retry(user,invoice,key);
        assertThat(jdbc.queryForObject("select count(*) from billing_invoices",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from billing_attempts",Integer.class)).isEqualTo(2);
        assertThatThrownBy(()->service.retry(other,invoice,UUID.randomUUID().toString())).isInstanceOf(BillingException.class);
    }
    @Test void renewalsPreserveOriginalMonthEndAndAreNotDuplicated() {
        activate(); now=Instant.parse("2026-02-28T01:00:00Z");
        service.prepareRenewals(); service.prepareRenewals(); service.processOne();
        assertThat(service.subscription(user).periodEnd()).isEqualTo(Instant.parse("2026-03-31T01:00:00Z"));
        assertThat(jdbc.queryForObject("select count(*) from billing_invoices",Integer.class)).isEqualTo(2);
    }
    @Test void cancelKeepsPaidPeriodAndStopsRenewalAndResumeNeedsConsent() {
        activate(); Instant end=service.subscription(user).periodEnd();
        service.cancel(user,UUID.randomUUID().toString());
        assertThat(service.subscription(user).entitlements().canCalculate()).isTrue();
        assertThat(service.subscription(user).nextChargeAt()).isNull();
        assertThatThrownBy(()->service.resume(user,UUID.randomUUID().toString(),"old")).isInstanceOf(BillingException.class);
        service.resume(user,UUID.randomUUID().toString(),BillingPolicy.CONSENT);
        assertThat(service.subscription(user).nextChargeAt()).isEqualTo(end);
        service.cancel(user,UUID.randomUUID().toString()); now=end; service.prepareRenewals(); service.processOne();
        assertThat(service.subscription(user).status()).isEqualTo("CANCELED");
        verify(gateway,times(1)).charge(anyString(),any(),anyString());
    }
    @Test void renewalDeclineHasExactThreeDayGraceBoundary() {
        activate(); now=service.subscription(user).periodEnd(); service.prepareRenewals();
        doThrow(new BillingGateway.Failure(true,"declined")).when(gateway).charge(anyString(),any(),anyString()); service.processOne();
        assertThat(service.subscription(user).status()).isEqualTo("PAST_DUE");
        assertThat(service.subscription(user).entitlements().canCalculate()).isTrue();
        now=now.plus(Duration.ofDays(3));
        assertThat(service.subscription(user).status()).isEqualTo("SUSPENDED");
        assertThat(service.subscription(user).entitlements().canCalculate()).isFalse();
    }
    @Test void longDowntimeDoesNotChargeMultiplePastMonths() {
        activate(); now=now.plus(Duration.ofDays(100)); service.prepareRenewals(); service.processOne();
        assertThat(jdbc.queryForObject("select count(*) from billing_invoices",Integer.class)).isEqualTo(1);
        verify(gateway,times(1)).charge(anyString(),any(),anyString());
    }
    @Test void replacingCardDoesNotChargeOrEraseDebtAndOldDeletionDoesNotDeleteNew() {
        activate(); String old=jdbc.queryForObject("select fingerprint from billing_methods",String.class);
        var r=service.register(user,UUID.randomUUID().toString(),new BillingRequests.Registration("REPLACE_METHOD",null,null,BillingPolicy.CONSENT)); complete(r);
        assertThat(service.methods(user)).hasSize(2);
        String current=service.methods(user).stream().filter(BillingViews.Method::current).findFirst().orElseThrow().id();
        assertThat(service.methods(user).stream().filter(m->m.id().equals(current)).findFirst().orElseThrow().status()).isEqualTo("ACTIVE");
        verify(gateway,times(1)).charge(anyString(),any(),anyString());
        service.retireOneMethod();
        assertThat(jdbc.queryForObject("select status from billing_methods where fingerprint=?",String.class,old)).isEqualTo("DELETED");
        assertThat(service.subscription(user).nextChargeAt()).isNotNull();
    }
    @Test void twoWorkersDoNotDispatchSameInvoice() throws Exception {
        checkout(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(i->{
            entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("worker wait timed out");
            var c=i.getArgument(1,BillingGateway.Charge.class); return new BillingGateway.Payment("parallel",c.orderId(),c.amount(),"KRW","DONE","test-merchant",now);
        }).when(gateway).charge(anyString(),any(),anyString());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->service.processOne());
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            pool.submit(()->service.processOne()).get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->service.cancel(user,UUID.randomUUID().toString())).isInstanceOf(BillingException.class);
            release.countDown(); first.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(gateway,times(1)).charge(anyString(),any(),anyString());
    }
    @Test void publicWebhookRequiresRawBodySignatureAndHandlesDuplicateSignatures() throws Exception {
        activate(); String encrypted=jdbc.queryForObject("select encrypted_key from billing_methods",String.class);
        String key=secrets.decrypt(encrypted);
        byte[] body=mapper.writeValueAsBytes(Map.of("eventType","BILLING_DELETED","data",Map.of("billingKey",key)));
        mvc.perform(post("/api/billing/webhooks/toss").contentType("application/json").content(body)).andExpect(status().isForbidden());
        String time="2026-01-31T01:00:00Z";
        String raw=new String(body,StandardCharsets.UTF_8)+":"+time;
        String sig="v1:bad,v1:"+Base64.getEncoder().encodeToString(BillingSecrets.hmac(WEBHOOK_KEY.getBytes(StandardCharsets.UTF_8),raw.getBytes(StandardCharsets.UTF_8)));
        for(int i=0;i<2;i++) mvc.perform(post("/api/billing/webhooks/toss").contentType("application/json").content(body)
                .header("tosspayments-webhook-transmission-time",time).header("tosspayments-webhook-signature",sig)).andExpect(status().isOk());
        assertThat(service.methods(user).getFirst().status()).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from billing_webhook_inbox",Integer.class)).isEqualTo(1);
        assertThat(service.subscription(user).entitlements().canCalculate()).isTrue();
        assertThat(service.subscription(user).nextChargeAt()).isNull();
    }
    @Test void apiUsesBearerAndNoStoreAndNeverExposesBillingKey() throws Exception {
        mvc.perform(get("/api/billing/subscription")).andExpect(status().isUnauthorized());
        activate();
        mvc.perform(get("/api/billing/subscription").with(jwt().jwt(j->j.subject(""+user))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("planId").value("seller")).andExpect(jsonPath("billingKey").doesNotExist());
        mvc.perform(get("/api/billing/payment-methods").with(jwt().jwt(j->j.subject(""+other))))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void expiredTrialCannotBypassByCallingActualWriteEndpoints() throws Exception {
        now=START.plus(Duration.ofDays(15));
        mvc.perform(put("/api/replenishment-settings").with(jwt().jwt(j->j.subject(""+user)))
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("TRIAL_ENDED"));
        mvc.perform(post("/api/results/1/scenarios").with(jwt().jwt(j->j.subject(""+user)))
                .contentType("application/json").content("{\"qty\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("TRIAL_ENDED"));
    }
    @Test void databaseFailureAfterApprovalRecoversWithoutSecondCharge() {
        checkout();
        String sql="update billing_invoices set status='PAID',paid_at=?,period_start=?,period_end=? where id=?";
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture storage failure"))
                .doCallRealMethod().when(ledger).update(eq(sql),any(Object[].class));
        assertThatThrownBy(()->service.processOne()).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(service.invoices(user,null).items().getFirst().status()).isEqualTo("OPEN");
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("DISPATCHING");
        String order=jdbc.queryForObject("select order_id from billing_attempts",String.class);
        when(gateway.lookup(order)).thenReturn(new BillingGateway.Payment("db-recovered",order,500,"KRW","DONE","test-merchant",now));
        now=now.plusSeconds(181); service.processOne();
        assertThat(service.invoices(user,null).items().getFirst().status()).isEqualTo("PAID");
        verify(gateway,times(1)).charge(anyString(),any(),anyString());
    }
    @Test void invalidMerchantAndOrderNeverGrantAccess() {
        checkout();
        doAnswer(i->new BillingGateway.Payment("wrong-merchant","wrong-order",500,"KRW","DONE","other-merchant",now))
                .when(gateway).charge(anyString(),any(),anyString());
        service.processOne();
        assertThat(service.subscription(user).planId()).isEqualTo("trial");
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("UNKNOWN");
    }
    @Test void unknownBeyondProviderIdempotencyWindowRequiresManualReview() {
        checkout(); doThrow(new BillingGateway.Failure(false,"timeout")).when(gateway).charge(anyString(),any(),anyString());
        service.processOne(); now=now.plus(Duration.ofDays(16)); service.processOne();
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThatThrownBy(()->service.retry(user,invoice(),UUID.randomUUID().toString())).isInstanceOf(BillingException.class);
        assertThatThrownBy(()->service.cancel(user,UUID.randomUUID().toString())).isInstanceOf(BillingException.class);
        verify(gateway,times(1)).charge(anyString(),any(),anyString()); verify(gateway,never()).lookup(anyString());
    }
    @Test void changedPersistedRequestDoesNotReachProvider() {
        checkout(); jdbc.update("update billing_invoices set amount=1 where user_id=?",user);
        service.processOne(); verify(gateway,never()).charge(anyString(),any(),anyString());
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("UNKNOWN");
    }
    @Test void controllerRoundTripBindsBearerAndServerPriceAndSessionOwnership() throws Exception {
        String key=UUID.randomUUID().toString();
        String request=mapper.writeValueAsString(Map.of("purpose","START_SUBSCRIPTION","planId","seller",
                "priceVersion",BillingPolicy.VERSION,"consentVersion",BillingPolicy.CONSENT,"amount",1));
        var result=mvc.perform(post("/api/billing/registration-sessions").with(jwt().jwt(j->j.subject(""+user)))
                .header("Idempotency-Key",key).contentType("application/json").content(request))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        var session=mapper.readTree(result.getResponse().getContentAsString());
        String id=session.path("sessionId").asText();
        String body=mapper.writeValueAsString(Map.of("authKey","fixture-auth","customerKey",session.path("customerKey").asText(),"state",session.path("state").asText()));
        mvc.perform(post("/api/billing/registration-sessions/"+id+"/complete").with(jwt().jwt(j->j.subject(""+user)))
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("paymentStatus").value("READY"))
                .andExpect(jsonPath("authKey").doesNotExist());
        mvc.perform(get("/api/billing/registration-sessions/"+id).with(jwt().jwt(j->j.subject(""+other))))
                .andExpect(status().isForbidden());
        assertThat(service.invoices(user,null).items().getFirst().amount()).isEqualTo(500);
        mvc.perform(post("/api/billing/subscription/cancel").with(jwt().jwt(j->j.subject(""+user)))
                .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk());
        service.processOne(); verify(gateway,never()).charge(anyString(),any(),anyString());
    }
    @Test void commonCalculationEntryBlocksReceiptAndProductTriggersAfterTrial() {
        long warehouse=warehouses.lockDefault(user).getId();
        now=START.plus(Duration.ofDays(15)); when(jobs.enabled()).thenReturn(true);
        assertThatThrownBy(()->calculations.request(user,warehouse,"RECEIPTS",false))
                .isInstanceOfSatisfying(BillingException.class,e->assertThat(e.code()).isEqualTo("TRIAL_ENDED"));
        assertThatThrownBy(()->calculations.request(user,warehouse,"PRODUCT",false))
                .isInstanceOfSatisfying(BillingException.class,e->assertThat(e.code()).isEqualTo("TRIAL_ENDED"));
        assertThatCode(()->calculations.request(user,warehouse,"UPLOAD",false)).doesNotThrowAnyException();
        assertThatCode(()->service.assertProductCapacity(user,0)).doesNotThrowAnyException();
    }
    @Test void publicWebhookLimitsBodyBeforeParsing() throws Exception {
        mvc.perform(post("/api/billing/webhooks/toss").contentType("application/json").content(new byte[65537])
                .header("tosspayments-webhook-transmission-time","fixture").header("tosspayments-webhook-signature","v1:fixture"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("code").value("WEBHOOK_TOO_LARGE"));
        verifyNoInteractions(gateway);
    }
    @Test void reviewCancelInvalidatesUnfinishedRegistration() {
        var r=registration(); service.cancel(user,UUID.randomUUID().toString());
        assertThatThrownBy(()->complete(r)).isInstanceOf(BillingException.class);
        service.processOne(); verifyNoInteractions(gateway);
    }
    @Test void reviewCancelWhileIssuingCannotReenableFirstCharge() throws Exception {
        var r=registration(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(i->{ entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("fixture wait");
            return new BillingGateway.Issued("late-issued-key",r.customerKey(),"****5678");
        }).when(gateway).issue(anyString(),anyString(),anyString());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(()->complete(r)); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            service.cancel(user,UUID.randomUUID().toString()); release.countDown();
            assertThat(future.get(5,TimeUnit.SECONDS).status()).isEqualTo("CANCELED");
        } finally { release.countDown(); }
        service.processOne(); verify(gateway,never()).charge(anyString(),any(),anyString());
        assertThat(service.subscription(user).cancelAtPeriodEnd()).isTrue();
    }
    @Test void reviewQueuedChargeUsesReplacementCard() {
        checkout();
        var r=service.register(user,UUID.randomUUID().toString(),new BillingRequests.Registration("REPLACE_METHOD",null,null,BillingPolicy.CONSENT));
        doReturn(new BillingGateway.Issued("replacement-key",r.customerKey(),"****9999")).when(gateway).issue(anyString(),anyString(),anyString());
        complete(r); service.processOne();
        verify(gateway).charge(eq("replacement-key"),any(),anyString());
    }
    @Test void reviewDeletedCardBeforeDispatchIsNotUnknownPayment() {
        checkout(); String key=secrets.decrypt(jdbc.queryForObject("select encrypted_key from billing_methods",String.class));
        service.deletedWebhook(key,BillingSecrets.hash("fixture deletion")); service.processOne();
        verify(gateway,never()).charge(anyString(),any(),anyString());
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("DECLINED");
        assertThatCode(()->service.cancel(user,UUID.randomUUID().toString())).doesNotThrowAnyException();
    }
    @Test void reviewActiveRenewalCannotRaceWithNewSubscriptionStart() {
        activate(); now=service.subscription(user).periodEnd();
        assertThatThrownBy(()->registration()).isInstanceOf(BillingException.class);
        service.prepareRenewals(); service.processOne();
        assertThat(service.invoices(user,null).items()).hasSize(2);
    }
    @Test void reviewStaleFirstChargeDoesNotChargeWithoutFreshIntent() {
        checkout(); now=now.plus(Duration.ofMinutes(16)); service.processOne();
        verify(gateway,never()).charge(anyString(),any(),anyString());
        assertThat(service.invoices(user,null).items().getFirst().status()).isEqualTo("VOID");
        assertThatCode(()->registration()).doesNotThrowAnyException();
    }
    @Test void reviewFirstPaidPeriodStartsAtApproval() {
        checkout(); now=now.plusSeconds(300); service.processOne();
        assertThat(service.subscription(user).periodStart()).isEqualTo(now);
        assertThat(service.subscription(user).periodEnd()).isEqualTo(BillingPolicy.boundary(now,1));
    }
    @Test void reviewDeletionBeforeIssueResponseCannotActivateDeletedKey() {
        var r=registration();
        doAnswer(i->{ service.deletedWebhook("already-deleted-key",BillingSecrets.hash("deleted-before-save"));
            return new BillingGateway.Issued("already-deleted-key",r.customerKey(),"****1234");
        }).when(gateway).issue(anyString(),anyString(),anyString());
        assertThat(complete(r).status()).isEqualTo("REJECTED");
        service.processOne(); verify(gateway,never()).charge(anyString(),any(),anyString());
        assertThat(service.methods(user).stream().noneMatch(BillingViews.Method::current)).isTrue();
    }
    @Test void reviewDefaultMethodCannotReferenceAnotherCustomer() {
        checkout(); service.subscription(other);
        String method=service.methods(user).getFirst().id();
        assertThatThrownBy(()->jdbc.update("update billing_subscriptions set default_method_id=? where user_id=?",method,other))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void reviewLookupPreservesProviderApprovalTime() {
        checkout(); doThrow(new BillingGateway.Failure(false,"timeout")).when(gateway).charge(anyString(),any(),anyString());
        service.processOne(); String order=jdbc.queryForObject("select order_id from billing_attempts",String.class);
        Instant approved=START.plusSeconds(5);
        when(gateway.lookup(order)).thenReturn(new BillingGateway.Payment("approval-time",order,500,"KRW","DONE","test-merchant",approved));
        now=now.plusSeconds(300); service.processOne();
        assertThat(service.invoices(user,null).items().getFirst().paidAt()).isEqualTo(approved);
        assertThat(service.subscription(user).periodStart()).isEqualTo(approved);
    }
    @Test void reviewMissingApprovalTimeCannotGrantPaidAccess() {
        checkout();
        doAnswer(i->{var c=i.getArgument(1,BillingGateway.Charge.class);
            return new BillingGateway.Payment("no-time",c.orderId(),c.amount(),"KRW","DONE","test-merchant",null);
        }).when(gateway).charge(anyString(),any(),anyString());
        service.processOne(); assertThat(service.subscription(user).planId()).isEqualTo("trial");
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("UNKNOWN");
    }
    @Test void reviewExpiredIssueResponseCannotActivateCard() {
        var r=registration();
        doAnswer(i->{ now=r.expiresAt(); return new BillingGateway.Issued("expired-issued",r.customerKey(),"****4444"); })
                .when(gateway).issue(anyString(),anyString(),anyString());
        assertThat(complete(r).status()).isEqualTo("EXPIRED");
        service.processOne(); verify(gateway,never()).charge(anyString(),any(),anyString());
        assertThat(service.methods(user).getFirst().status()).isEqualTo("RETIRED");
    }
    @Test void reviewRestartDoesNotRestoreGraceBeforeApproval() {
        activate(); Instant end=service.subscription(user).periodEnd(); service.cancel(user,UUID.randomUUID().toString());
        now=end.plus(Duration.ofDays(1)); checkout();
        assertThat(service.subscription(user).entitlements().canCalculate()).isFalse();
        assertThat(service.subscription(user).status()).isEqualTo("CANCELED");
        service.processOne();
        assertThat(service.subscription(user).status()).isEqualTo("ACTIVE");
        assertThat(service.subscription(user).cancelAtPeriodEnd()).isFalse();
    }
    @Test void reviewRestartDeclineCanRetryWithoutRestoringOldEntitlement() {
        activate(); Instant end=service.subscription(user).periodEnd(); service.cancel(user,UUID.randomUUID().toString());
        now=end.plusSeconds(1); checkout();
        doThrow(new BillingGateway.Failure(true,"declined")).when(gateway).charge(anyString(),any(),anyString()); service.processOne();
        assertThat(service.subscription(user).entitlements().canCalculate()).isFalse();
        assertThatCode(()->service.retry(user,invoice(),UUID.randomUUID().toString())).doesNotThrowAnyException();
        doAnswer(i->{var c=i.getArgument(1,BillingGateway.Charge.class); return new BillingGateway.Payment("restart-paid",c.orderId(),c.amount(),"KRW","DONE","test-merchant",now);})
                .when(gateway).charge(anyString(),any(),anyString());
        service.processOne(); assertThat(service.subscription(user).status()).isEqualTo("ACTIVE");
    }
    @Test void reviewConcurrentDeletionDuringMethodInsertCannotLoseRevocation() throws Exception {
        var r=registration(); var inserting=new CountDownLatch(1); var release=new CountDownLatch(1);
        doReturn(new BillingGateway.Issued("concurrent-delete",r.customerKey(),"****5555")).when(gateway).issue(anyString(),anyString(),anyString());
        doAnswer(i->{ inserting.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("fixture wait"); return i.callRealMethod(); })
                .when(ledger).update(startsWith("insert into billing_methods"),any(Object[].class));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var completing=pool.submit(()->complete(r)); assertThat(inserting.await(5,TimeUnit.SECONDS)).isTrue();
            var deleting=pool.submit(()->service.deletedWebhook("concurrent-delete",BillingSecrets.hash("concurrent-event")));
            try { deleting.get(250,TimeUnit.MILLISECONDS); } catch(java.util.concurrent.TimeoutException expected) { /* serialized registration */ }
            release.countDown(); completing.get(5,TimeUnit.SECONDS); deleting.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(service.methods(user).getFirst().status()).isEqualTo("DELETED");
        service.processOne(); verify(gateway,never()).charge(anyString(),any(),anyString());
    }
    @Test void reviewVerifiedAbortedLookupAllowsExplicitRetryWithoutSecondAutomaticCharge() {
        checkout(); doThrow(new BillingGateway.Failure(false,"timeout")).when(gateway).charge(anyString(),any(),anyString());
        service.processOne(); String order=jdbc.queryForObject("select order_id from billing_attempts",String.class);
        when(gateway.lookup(order)).thenReturn(new BillingGateway.Payment("aborted-payment",order,500,"KRW","ABORTED","test-merchant",null));
        now=now.plusSeconds(181); service.processOne();
        assertThat(service.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("DECLINED");
        assertThatCode(()->service.retry(user,invoice(),UUID.randomUUID().toString())).doesNotThrowAnyException();
        verify(gateway,times(1)).charge(anyString(),any(),anyString());
    }
    @Test void productCapacityIsCheckedOnServer() {
        service.assertProductCapacity(user,300);
        assertThatThrownBy(()->service.assertProductCapacity(user,301)).isInstanceOf(BillingException.class);
    }
    @Test void cancelBeforeWorkerPreventsInitialCharge() {
        checkout(); service.cancel(user,UUID.randomUUID().toString()); service.processOne();
        verify(gateway,never()).charge(anyString(),any(),anyString());
        assertThat(service.invoices(user,null).items().getFirst().status()).isEqualTo("VOID");
    }

    private String paidInvoice() {
        activate();
        return jdbc.queryForObject("select id from billing_invoices where user_id=? and status='PAID'",String.class,user);
    }
    private BillingGateway.Payment refundPayment(String invoice,String status,long balance) {
        var row=jdbc.queryForMap("select a.payment_key,a.order_id,i.amount,i.paid_at from billing_attempts a join billing_invoices i on i.id=a.invoice_id where a.invoice_id=? and a.status='SUCCEEDED'",invoice);
        return new BillingGateway.Payment(row.get("payment_key").toString(),row.get("order_id").toString(),((Number)row.get("amount")).longValue(),"KRW",status,"test-merchant",START,balance,"CANCELED".equals(status)?now:null);
    }
    @Test void fullRefundPreservesApprovalAndEndsAccessOnlyAfterConfirmedCancel() throws Exception {
        String invoice=paidInvoice();
        when(gateway.lookup(anyString())).thenReturn(refundPayment(invoice,"DONE",500));
        when(gateway.cancel(anyString(),anyString(),anyString())).thenReturn(refundPayment(invoice,"CANCELED",0));
        mvc.perform(post("/api/billing/invoices/"+invoice+"/cancel-payment").with(jwt().jwt(j->j.subject(Long.toString(user))))
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"reason\":\"테스트 전액 취소\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        assertThat(service.subscription(user).entitlements().canCalculate()).isFalse();
        assertThat(jdbc.queryForObject("select status from billing_attempts where invoice_id=?",String.class,invoice)).isEqualTo("SUCCEEDED");
        assertThat(service.invoices(user,null).items().getFirst().refundStatus()).isEqualTo("CANCELED");
        assertThat(service.invoices(user,null).items().getFirst().canceledAt()).isNotNull();
        assertThat(service.subscription(user).nextChargeAt()).isNull();
    }
    @Test void refundIsOwnedAndDoesNotCallProviderForAnotherUser() {
        String invoice=paidInvoice();
        assertThatThrownBy(()->refunds.cancel(other,invoice,UUID.randomUUID().toString(),"test")).isInstanceOf(com.ipgo.server.exception.BillingException.class);
        verify(gateway,never()).cancel(anyString(),anyString(),anyString());
    }
    @Test void repeatedRefundUsesOneProviderRequestAndRejectsChangedBody() {
        String invoice=paidInvoice(),key=UUID.randomUUID().toString();
        when(gateway.lookup(anyString())).thenReturn(refundPayment(invoice,"DONE",500));
        when(gateway.cancel(anyString(),anyString(),anyString())).thenReturn(refundPayment(invoice,"CANCELED",0));
        refunds.cancel(user,invoice,key,"test");
        refunds.cancel(user,invoice,key,"test");
        refunds.cancel(user,invoice,UUID.randomUUID().toString(),"test");
        assertThatThrownBy(()->refunds.cancel(user,invoice,key,"different")).isInstanceOf(com.ipgo.server.exception.BillingException.class);
        verify(gateway,times(1)).cancel(anyString(),eq("test"),anyString());
    }
    @Test void lostCancelResponseReconcilesByLookupWithoutSecondCancel() {
        String invoice=paidInvoice();
        when(gateway.lookup(anyString())).thenReturn(refundPayment(invoice,"DONE",500),refundPayment(invoice,"CANCELED",0));
        when(gateway.cancel(anyString(),anyString(),anyString())).thenThrow(new BillingGateway.Failure(false,"TIMEOUT"));
        assertThat(refunds.cancel(user,invoice,UUID.randomUUID().toString(),"test").status()).isEqualTo("UNKNOWN");
        assertThat(service.subscription(user).entitlements().canCalculate()).isTrue();
        assertThatThrownBy(()->service.resume(user,UUID.randomUUID().toString(),BillingPolicy.CONSENT)).isInstanceOf(com.ipgo.server.exception.BillingException.class);
        now=START.plusSeconds(181); refunds.processOne();
        assertThat(service.invoices(user,null).items().getFirst().refundStatus()).isEqualTo("CANCELED");
        assertThat(service.subscription(user).entitlements().canCalculate()).isFalse();
        verify(gateway,times(1)).cancel(anyString(),anyString(),anyString());
    }
    @Test void uncertainRetryKeepsProviderIdempotencyKey() {
        String invoice=paidInvoice();
        when(gateway.lookup(anyString())).thenReturn(refundPayment(invoice,"DONE",500));
        when(gateway.cancel(anyString(),anyString(),anyString())).thenThrow(new BillingGateway.Failure(false,"TIMEOUT"));
        refunds.cancel(user,invoice,UUID.randomUUID().toString(),"test");
        now=START.plusSeconds(181); refunds.processOne();
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway,times(2)).cancel(anyString(),eq("test"),keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }
    @Test void mismatchedRefundProviderResultDoesNotRevokeEntitlement() {
        String invoice=paidInvoice();
        var p=refundPayment(invoice,"DONE",500);
        when(gateway.lookup(anyString())).thenReturn(new BillingGateway.Payment(p.paymentKey(),p.orderId(),999,"KRW","DONE","test-merchant",START,999,null));
        assertThat(refunds.cancel(user,invoice,UUID.randomUUID().toString(),"test").status()).isEqualTo("UNKNOWN");
        assertThat(service.subscription(user).entitlements().canCalculate()).isTrue();
        verify(gateway,never()).cancel(anyString(),anyString(),anyString());
    }
    @Test void partialCancellationIsNotReportedAsFullRefund() {
        String invoice=paidInvoice();
        when(gateway.lookup(anyString())).thenReturn(refundPayment(invoice,"PARTIAL_CANCELED",250));
        assertThat(refunds.cancel(user,invoice,UUID.randomUUID().toString(),"test").status()).isEqualTo("UNKNOWN");
        verify(gateway,never()).cancel(anyString(),anyString(),anyString());
    }
    @Test void concurrentRefundDoesNotSendSecondRequest() throws Exception {
        String invoice=paidInvoice();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(gateway.lookup(anyString())).thenAnswer(a->{ entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); return refundPayment(invoice,"DONE",500); });
        when(gateway.cancel(anyString(),anyString(),anyString())).thenReturn(refundPayment(invoice,"CANCELED",0));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->refunds.cancel(user,invoice,UUID.randomUUID().toString(),"test"));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(refunds.cancel(user,invoice,UUID.randomUUID().toString(),"test").status()).isEqualTo("PROCESSING");
            } finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS).status()).isEqualTo("CANCELED");
        }
        verify(gateway,times(1)).cancel(anyString(),anyString(),anyString());
    }
}
