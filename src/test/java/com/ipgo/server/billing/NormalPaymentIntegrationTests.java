package com.ipgo.server.billing;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.infrastructure.BillingGateway;
import com.ipgo.server.infrastructure.NormalPaymentGateway;
import com.ipgo.server.service.BillingService;
import com.ipgo.server.service.NormalPaymentService;
import com.ipgo.server.util.BillingPolicy;
import com.ipgo.server.exception.BillingException;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class NormalPaymentIntegrationTests extends DatabaseIntegrationTest {
    @Autowired NormalPaymentService normal;
    @Autowired BillingService billing;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired tools.jackson.databind.json.JsonMapper mapper;
    @MockitoBean NormalPaymentGateway gateway;
    @MockitoBean BillingGateway legacy;
    @MockitoBean Clock clock;
    // #208 기본은 실제 값(환불 제한 꺼짐)이고, 환불 제한 테스트에서만 켠다.
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.ipgo.server.config.RefundPolicyProperties refundPolicy;
    static final Instant START=Instant.parse("2026-01-31T01:00:00Z");
    Instant now;
    long user,other;
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("ipgo.payment.enabled",()->"true");
        r.add("ipgo.payment.secret-key",()->"test_gsk_fixture_only");
        r.add("ipgo.billing.enabled",()->"true");
        r.add("ipgo.billing.worker-enabled",()->"false");
        r.add("ipgo.billing.secret-key",()->"test_sk_fixture_only");
        r.add("ipgo.billing.security-key",()->"fixture");
        r.add("ipgo.billing.merchant-id",()->"legacy-mid");
        r.add("ipgo.billing.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("ipgo.billing.lookup-key",()->{byte[] b=new byte[32];Arrays.fill(b,(byte)1);return Base64.getEncoder().encodeToString(b);});
        r.add("ipgo.engine.enabled",()->"false");
    }
    @BeforeEach void setup() throws Exception {
        com.ipgo.server.support.UploadTestDatabase.withdrawalSchema(jdbc);
        if(jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name)='billing_normal_orders'",Integer.class)==0) {
            String sql=new org.springframework.core.io.ClassPathResource("db/migration/V27__create_normal_payment_orders.sql").getContentAsString(StandardCharsets.UTF_8).split("-- PostgreSQL 전용")[0].replace("refund_status VARCHAR(24) CHECK", "refund_status VARCHAR(24) CONSTRAINT billing_normal_orders_refund_status_check CHECK");
            for(String s:sql.split(";")) if(!s.isBlank()) jdbc.execute(s);
            String upgrade=new org.springframework.core.io.ClassPathResource("db/migration/V30__support_virtual_account_refunds.sql").getContentAsString(StandardCharsets.UTF_8).split("-- PostgreSQL 전용")[0];
            for(String statement:upgrade.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        }
        // #208 주문별 결제 동의 버전 · 시각(V32)
        if(jdbc.queryForObject("select count(*) from information_schema.columns where lower(table_name)='billing_normal_orders' and lower(column_name)='consent_version'",Integer.class)==0) {
            String consent=new org.springframework.core.io.ClassPathResource("db/migration/V32__record_normal_payment_consent.sql").getContentAsString(StandardCharsets.UTF_8);
            for(String statement:consent.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        }
        now=START; when(clock.instant()).thenAnswer(i->now);
        user=addUser(); other=addUser();
        when(gateway.confirm(anyString(),anyString(),anyLong(),anyString())).thenAnswer(i->new BillingGateway.Payment(i.getArgument(0),i.getArgument(1),i.getArgument(2),"KRW","DONE","new-mid",now));
    }
    long addUser() {
        jdbc.update("insert into users(email,password_hash,created_at) values (?,'fixture',?)",UUID.randomUUID()+"@example.test",java.sql.Timestamp.from(START.minusSeconds(3600)));
        return jdbc.queryForObject("select max(id) from users",Long.class);
    }
    @AfterEach void cleanup() {
        for(String table:List.of("billing_normal_orders","billing_commands","billing_invoices","billing_subscriptions")) jdbc.update("delete from "+table+" where user_id in (?,?)",user,other);
        jdbc.update("delete from users where id in (?,?)",user,other);
    }
    NormalPaymentService.Checkout order() { return normal.create(user,UUID.randomUUID().toString(),"seller",BillingPolicy.VERSION,NormalPaymentService.CONSENT); }
    @Test void priceAndConsentAreServerOwnedAndCreateIsIdempotent() {
        String key=UUID.randomUUID().toString();
        var a=normal.create(user,key,"seller",BillingPolicy.VERSION,NormalPaymentService.CONSENT);
        assertThat(normal.create(user,key,"seller",BillingPolicy.VERSION,NormalPaymentService.CONSENT).orderId()).isEqualTo(a.orderId());
        assertThat(order().orderId()).isEqualTo(a.orderId());
        assertThat(a.amount()).isEqualTo(500);
        assertThat(billing.plans()).allSatisfy(p->assertThat(p.interval()).isEqualTo("ONE_TIME"));
        assertThatThrownBy(()->normal.create(user,key,"business",BillingPolicy.VERSION,NormalPaymentService.CONSENT)).isInstanceOf(BillingException.class);
        assertThatThrownBy(()->normal.create(other,UUID.randomUUID().toString(),"seller","old",NormalPaymentService.CONSENT)).isInstanceOf(BillingException.class);
        assertThatThrownBy(()->normal.create(other,UUID.randomUUID().toString(),"seller",BillingPolicy.VERSION,BillingPolicy.CONSENT)).isInstanceOf(BillingException.class);
        verifyNoInteractions(gateway);
    }
    @Test void ownerAndAmountChecksHappenBeforeProviderCall() {
        var o=order();
        assertThatThrownBy(()->normal.confirm(other,o.orderId(),"PaymentKey",500)).isInstanceOf(BillingException.class);
        assertThatThrownBy(()->normal.confirm(user,o.orderId(),"PaymentKey",1)).isInstanceOf(BillingException.class);
        verifyNoInteractions(gateway);
    }
    @Test void approvalGrantsOneCalendarMonthOnceAndNeverQueuesRenewals() {
        var o=order();
        assertThat(normal.confirm(user,o.orderId(),"CaseKey",500).status()).isEqualTo("PAID");
        assertThat(normal.confirm(user,o.orderId(),"CaseKey",500).status()).isEqualTo("PAID");
        verify(gateway,times(1)).confirm(eq("CaseKey"),eq(o.orderId()),eq(500L),anyString());
        var s=billing.subscription(user);
        assertThat(s.periodEnd()).isEqualTo(Instant.parse("2026-02-28T01:00:00Z"));
        assertThat(s.nextChargeAt()).isNull();
        assertThat(s.cancelAtPeriodEnd()).isTrue();
        assertThat(billing.invoices(user,null).items().getFirst().paymentStatus()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(this::order).isInstanceOf(BillingException.class);
        now=s.periodEnd(); billing.prepareRenewals(); billing.processOne();
        assertThat(billing.subscription(user).entitlements().canCalculate()).isFalse();
        assertThat(billing.subscription(user).status()).isEqualTo("CANCELED");
        verifyNoInteractions(legacy);
        assertThat(order().orderId()).isNotEqualTo(o.orderId());
    }
    @Test void depositWaitDoesNotGrantAccessAndReconcilesByKey() {
        var o=order();
        when(gateway.confirm(anyString(),anyString(),anyLong(),anyString())).thenReturn(new BillingGateway.Payment("key",o.orderId(),500,"KRW","WAITING_FOR_DEPOSIT","new-mid",null));
        assertThat(normal.confirm(user,o.orderId(),"key",500).status()).isEqualTo("PENDING");
        assertThat(billing.subscription(user).planId()).isEqualTo("trial");
        assertThatThrownBy(this::order).isInstanceOf(BillingException.class);
        now=now.plusSeconds(61);
        when(gateway.lookup("key")).thenReturn(new BillingGateway.Payment("key",o.orderId(),500,"KRW","DONE","new-mid",now));
        normal.processOne();
        assertThat(normal.get(user,o.orderId()).status()).isEqualTo("PAID");
        verify(gateway,times(1)).confirm(anyString(),anyString(),anyLong(),anyString());
    }
    @Test void lostApprovalResponseIsOnlyQueriedNeverReconfirmed() {
        var o=order();
        when(gateway.confirm(anyString(),anyString(),anyLong(),anyString())).thenThrow(new BillingGateway.Failure(false,"TIMEOUT"));
        assertThat(normal.confirm(user,o.orderId(),"key",500).status()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->normal.confirm(user,o.orderId(),"different",500)).isInstanceOf(BillingException.class);
        assertThat(normal.confirm(user,o.orderId(),"key",500).status()).isEqualTo("UNKNOWN");
        now=now.plusSeconds(61);
        when(gateway.lookup("key")).thenReturn(new BillingGateway.Payment("key",o.orderId(),500,"KRW","DONE","new-mid",START));
        assertThat(normal.get(user,o.orderId()).status()).isEqualTo("PAID");
        verify(gateway,times(1)).confirm(anyString(),anyString(),anyLong(),anyString());
    }
    @Test void mismatchedProviderAmountNeverGrantsPaidEntitlement() {
        var o=order();
        when(gateway.confirm(anyString(),anyString(),anyLong(),anyString())).thenReturn(new BillingGateway.Payment("key",o.orderId(),1,"KRW","DONE","new-mid",now));
        assertThat(normal.confirm(user,o.orderId(),"key",500).status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(billing.subscription(user).planId()).isEqualTo("trial");
    }
    @Test void fullRefundOnlyEndsEntitlementAfterProviderConfirmation() {
        var o=order(); normal.confirm(user,o.orderId(),"key",500);
        var paid=new BillingGateway.Payment("key",o.orderId(),500,"KRW","DONE","new-mid",now);
        when(gateway.lookup("key")).thenReturn(paid);
        when(gateway.cancel(eq("key"),anyString(),anyString())).thenReturn(new BillingGateway.Payment("key",o.orderId(),500,"KRW","CANCELED","new-mid",START,0,now));
        String invoice=normal.get(user,o.orderId()).invoiceId();
        assertThat(normal.refund(user,invoice,UUID.randomUUID().toString(),"테스트 취소").status()).isEqualTo("CANCELED");
        assertThat(normal.refund(user,invoice,UUID.randomUUID().toString(),"테스트 취소").status()).isEqualTo("CANCELED");
        verify(gateway,times(1)).cancel(eq("key"),anyString(),anyString());
        assertThat(billing.subscription(user).entitlements().canCalculate()).isFalse();
        assertThat(billing.invoices(user,null).items().getFirst().refundStatus()).isEqualTo("CANCELED");
    }
    @Test void expiredCheckoutAndWithdrawnUserCannotConfirm() {
        var o=order(); now=now.plusSeconds(1801);
        assertThatThrownBy(()->normal.confirm(user,o.orderId(),"key",500)).isInstanceOf(BillingException.class);
        var fresh=order();
        jdbc.update("update users set withdrawn_at=? where id=?",java.sql.Timestamp.from(now),user);
        assertThatThrownBy(()->normal.confirm(user,fresh.orderId(),"key",500)).isInstanceOf(BillingException.class);
        verifyNoInteractions(gateway);
    }
    @Test void httpRoundTripUsesStoredPriceAndProtectsOwnerAndLegacyRoutes() throws Exception {
        var auth=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject(Long.toString(user)));
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/billing/payments").with(auth)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("planId","seller","priceVersion",BillingPolicy.VERSION,"consentVersion",NormalPaymentService.CONSENT,"amount",1))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("amount").value(500))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andReturn();
        String id=mapper.readTree(response.getResponse().getContentAsString()).path("orderId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/billing/payments/"+id+"/confirm").with(auth)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("paymentKey","key","amount",500))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("status").value("PAID"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/billing/payments/"+id)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject(Long.toString(other)))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/billing/subscription/resume").with(auth)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("consentVersion",BillingPolicy.CONSENT))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }
    @Test void unknownRefundCanBeReconciledFromAccountWithoutAnotherApproval() {
        var o=order(); normal.confirm(user,o.orderId(),"key",500);
        when(gateway.lookup("key")).thenReturn(new BillingGateway.Payment("key",o.orderId(),500,"KRW","DONE","new-mid",START));
        when(gateway.cancel(eq("key"),anyString(),anyString())).thenThrow(new BillingGateway.Failure(false,"TIMEOUT"));
        String inv=normal.get(user,o.orderId()).invoiceId();
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"테스트").status()).isEqualTo("UNKNOWN");
        assertThat(billing.subscription(user).entitlements().canCalculate()).isTrue();
        now=now.plusSeconds(61);
        when(gateway.lookup("key")).thenReturn(new BillingGateway.Payment("key",o.orderId(),500,"KRW","CANCELED","new-mid",START,0,now));
        assertThat(normal.get(user,o.orderId()).refundStatus()).isEqualTo("CANCELED");
        verify(gateway,times(1)).cancel(eq("key"),anyString(),anyString());
        assertThat(billing.subscription(user).entitlements().canCalculate()).isFalse();
    }

    @Test void concurrentConfirmRequestsReachProviderOnlyOnce() throws Exception {
        var o=order();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(gateway.confirm(anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{
            entered.countDown();
            if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return new BillingGateway.Payment("key",o.orderId(),500,"KRW","DONE","new-mid",START);
        });
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->normal.confirm(user,o.orderId(),"key",500));
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(normal.confirm(user,o.orderId(),"key",500).status()).isEqualTo("CONFIRMING");
            } finally { release.countDown(); }
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("PAID");
        }
        verify(gateway,times(1)).confirm(anyString(),anyString(),anyLong(),anyString());
    }

    @Test void oldDepositWaitCannotStarveAnotherUsersUnknownPayment() {
        var early=order();
        when(gateway.confirm(eq("early-key"),anyString(),anyLong(),anyString()))
                .thenReturn(new BillingGateway.Payment("early-key",early.orderId(),500,"KRW","WAITING_FOR_DEPOSIT","new-mid",null));
        normal.confirm(user,early.orderId(),"early-key",500);
        now=START.plusSeconds(1);
        var later=normal.create(other,UUID.randomUUID().toString(),"seller",BillingPolicy.VERSION,NormalPaymentService.CONSENT);
        when(gateway.confirm(eq("later-key"),anyString(),anyLong(),anyString())).thenThrow(new BillingGateway.Failure(false,"TIMEOUT"));
        normal.confirm(other,later.orderId(),"later-key",500);
        when(gateway.lookup("early-key")).thenReturn(new BillingGateway.Payment("early-key",early.orderId(),500,"KRW","WAITING_FOR_DEPOSIT","new-mid",null));
        when(gateway.lookup("later-key")).thenReturn(new BillingGateway.Payment("later-key",later.orderId(),500,"KRW","DONE","new-mid",START.plusSeconds(1)));
        now=START.plusSeconds(61); normal.processOne();
        now=START.plusSeconds(121); normal.processOne();
        // Reading through get() would itself reconcile the order and hide worker starvation.
        assertThat(jdbc.queryForObject("select status from billing_normal_orders where id=?",String.class,later.orderId())).isEqualTo("PAID");
        verify(gateway).lookup("later-key");
        verify(gateway,times(2)).confirm(anyString(),anyString(),anyLong(),anyString());
    }

    private BillingGateway.Payment va(String order,String status,String refund) {
        return new BillingGateway.Payment("key",order,500,"KRW",status,"new-mid",status.equals("WAITING_FOR_DEPOSIT")?null:START,
                status.equals("CANCELED")?0:500,status.equals("CANCELED")?now:null,"가상계좌",refund);
    }
    private NormalPaymentGateway.RefundAccount account() { return new NormalPaymentGateway.RefundAccount("88","1234567890","합성 예금주"); }
    private NormalPaymentService.Checkout virtualOrder(boolean paid) {
        var o=order();
        when(gateway.confirm(anyString(),anyString(),anyLong(),anyString())).thenReturn(va(o.orderId(),paid?"DONE":"WAITING_FOR_DEPOSIT","NONE"));
        normal.confirm(user,o.orderId(),"key",500);return o;
    }
    @Test void unpaidVirtualAccountCancellationDoesNotNeedAccountOrEndTrial() {
        var o=virtualOrder(false);String inv=normal.get(user,o.orderId()).invoiceId();
        var before=billing.subscription(user);
        when(gateway.lookup("key")).thenReturn(new BillingGateway.Payment("key",o.orderId(),500,"KRW","WAITING_FOR_DEPOSIT","new-mid",null,0,null,"가상계좌","NONE"));
        when(gateway.cancel(eq("key"),anyString(),anyString())).thenReturn(new BillingGateway.Payment("key",o.orderId(),500,"KRW","CANCELED","new-mid",null,0,now,"가상계좌","NONE"));
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"발급 취소").status()).isEqualTo("CANCELED");
        assertThat(billing.subscription(user)).isEqualTo(before);
        assertThat(billing.invoices(user,null).items().getFirst().status()).isEqualTo("VOID");
        verify(gateway,never()).cancel(anyString(),anyString(),anyString(),any());
    }
    @Test void paidVirtualRefundRequiresAccountWithoutSendingCancellation() {
        var o=virtualOrder(true);when(gateway.lookup("key")).thenReturn(va(o.orderId(),"DONE","NONE"));
        assertThat(normal.refund(user,normal.get(user,o.orderId()).invoiceId(),UUID.randomUUID().toString(),"환불").status()).isEqualTo("ACCOUNT_REQUIRED");
        assertThat(billing.subscription(user).entitlements().canCalculate()).isTrue();
        assertThat(billing.invoices(user,null).items().getFirst().paymentMethod()).isEqualTo("가상계좌");
        verify(gateway,never()).cancel(anyString(),anyString(),anyString());
        verify(gateway,never()).cancel(anyString(),anyString(),anyString(),any());
    }
    @Test void unknownRefundRetainsEncryptedAccountAndRetriesIdenticalProviderKey() {
        var o=virtualOrder(true);String inv=normal.get(user,o.orderId()).invoiceId();
        when(gateway.lookup("key")).thenReturn(va(o.orderId(),"DONE","NONE"));
        when(gateway.cancel(eq("key"),anyString(),anyString(),any())).thenThrow(new BillingGateway.Failure(false,"TIMEOUT"));
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"환불",account()).status()).isEqualTo("UNKNOWN");
        String encrypted=jdbc.queryForObject("select refund_account_ciphertext from billing_normal_orders where id=?",String.class,o.orderId());
        assertThat(encrypted).startsWith("v1.").doesNotContain("1234567890","합성 예금주");
        var changed=new NormalPaymentGateway.RefundAccount("20","9876543210","다른 합성");
        assertThatThrownBy(()->normal.refund(user,inv,UUID.randomUUID().toString(),"환불",changed)).isInstanceOf(BillingException.class);
        now=now.plusSeconds(61);
        when(gateway.cancel(eq("key"),anyString(),anyString(),eq(account()))).thenReturn(va(o.orderId(),"CANCELED","COMPLETED"));
        normal.processOne();
        assertThat(normal.get(user,o.orderId()).refundStatus()).isEqualTo("CANCELED");
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway,times(2)).cancel(eq("key"),eq("환불"),keys.capture(),eq(account()));
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
        assertThat(jdbc.queryForObject("select refund_account_ciphertext from billing_normal_orders where id=?",String.class,o.orderId())).isNull();
    }
    @Test void rejectedAccountCanBeCorrectedWithNewProviderKey() {
        var o=virtualOrder(true);String inv=normal.get(user,o.orderId()).invoiceId();
        when(gateway.lookup("key")).thenReturn(va(o.orderId(),"DONE","NONE"));
        when(gateway.cancel(eq("key"),anyString(),anyString(),any())).thenThrow(new BillingGateway.Failure(true,"REFUND_ACCOUNT_INVALID"));
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"환불",account()).status()).isEqualTo("ACCOUNT_REQUIRED");
        assertThat(jdbc.queryForObject("select refund_account_ciphertext from billing_normal_orders where id=?",String.class,o.orderId())).isNull();
        var corrected=new NormalPaymentGateway.RefundAccount("20","9876543210","다른 합성");
        when(gateway.cancel(eq("key"),anyString(),anyString(),eq(corrected))).thenReturn(va(o.orderId(),"CANCELED","COMPLETED"));
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"환불 사유 수정",corrected).status()).isEqualTo("CANCELED");
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);verify(gateway,times(2)).cancel(eq("key"),anyString(),keys.capture(),any());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }
    @Test void bankRefundPendingIsNotCompletedAndRecoveryOnlyLooksUp() {
        var o=virtualOrder(true);String inv=normal.get(user,o.orderId()).invoiceId();
        when(gateway.lookup("key")).thenReturn(va(o.orderId(),"DONE","NONE"));
        when(gateway.cancel(eq("key"),anyString(),anyString(),eq(account()))).thenReturn(va(o.orderId(),"CANCELED","PENDING"));
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"환불",account()).status()).isEqualTo("PROCESSING");
        assertThat(billing.subscription(user).status()).isEqualTo("CANCELED");
        assertThat(billing.invoices(user,null).items().getFirst().status()).isEqualTo("VOID");
        assertThat(jdbc.queryForObject("select refund_account_ciphertext from billing_normal_orders where id=?",String.class,o.orderId())).isNull();
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"환불",account()).status()).isEqualTo("PROCESSING");
        now=now.plusSeconds(61);when(gateway.lookup("key")).thenReturn(va(o.orderId(),"CANCELED","COMPLETED"));normal.processOne();
        assertThat(normal.get(user,o.orderId()).refundStatus()).isEqualTo("CANCELED");
        verify(gateway,times(1)).cancel(eq("key"),anyString(),anyString(),any());
    }
    @Test void bankRefundFailureNeedsReviewWithoutBlindResending() {
        var o=virtualOrder(true);when(gateway.lookup("key")).thenReturn(va(o.orderId(),"DONE","NONE"));
        when(gateway.cancel(eq("key"),anyString(),anyString(),eq(account()))).thenReturn(va(o.orderId(),"CANCELED","FAILED"));
        assertThat(normal.refund(user,normal.get(user,o.orderId()).invoiceId(),UUID.randomUUID().toString(),"환불",account()).status()).isEqualTo("REVIEW_REQUIRED");
        now=now.plusSeconds(120);normal.processOne();
        verify(gateway,times(1)).cancel(eq("key"),anyString(),anyString(),any());
        assertThat(jdbc.queryForObject("select refund_account_ciphertext from billing_normal_orders where id=?",String.class,o.orderId())).isNull();
    }
    @Test void racingDepositRequiresRefundAccountAndPreservesPaidAccessUntilCancel() {
        var o=virtualOrder(false);String inv=normal.get(user,o.orderId()).invoiceId();
        when(gateway.lookup("key")).thenReturn(va(o.orderId(),"DONE","NONE"));
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"환불").status()).isEqualTo("ACCOUNT_REQUIRED");
        assertThat(billing.subscription(user).status()).isEqualTo("ACTIVE");
        assertThat(billing.invoices(user,null).items().getFirst().paidAt()).isEqualTo(START);
        when(gateway.cancel(eq("key"),anyString(),anyString(),eq(account()))).thenReturn(va(o.orderId(),"CANCELED","COMPLETED"));
        assertThat(normal.refund(user,inv,UUID.randomUUID().toString(),"환불",account()).status()).isEqualTo("CANCELED");
        assertThat(billing.subscription(user).status()).isEqualTo("CANCELED");
    }
    private void restrictRefunds() {
        when(refundPolicy.restricted()).thenReturn(true);
        when(refundPolicy.consentVersion()).thenReturn(com.ipgo.server.config.RefundPolicyProperties.CONSENT_NO_REFUND);
    }
    private static String code(Throwable t) { return ((BillingException) t).code(); }

    // #208 환불 제한을 켜면 새 결제 동의 버전(v2)만 받고, 주문에 버전 · 시각을 남기며, 결제 완료 건의 새 환불은 거절한다.
    @Test void restrictedPolicyRequiresNewConsentAndRejectsRefundOfPaidOrder() {
        restrictRefunds();
        assertThat(billing.plans().getFirst().consentVersion()).isEqualTo(com.ipgo.server.config.RefundPolicyProperties.CONSENT_NO_REFUND);
        assertThatThrownBy(()->normal.create(user,UUID.randomUUID().toString(),"seller",BillingPolicy.VERSION,NormalPaymentService.CONSENT))
                .satisfies(t->assertThat(code(t)).isEqualTo("BILLING_CONSENT_REQUIRED"));
        var o=normal.create(user,UUID.randomUUID().toString(),"seller",BillingPolicy.VERSION,com.ipgo.server.config.RefundPolicyProperties.CONSENT_NO_REFUND);
        var row=jdbc.queryForMap("select consent_version,consented_at from billing_normal_orders where id=?",o.orderId());
        assertThat(row.get("consent_version")).isEqualTo(com.ipgo.server.config.RefundPolicyProperties.CONSENT_NO_REFUND);
        assertThat(row.get("consented_at")).isNotNull();

        normal.confirm(user,o.orderId(),"key",500);
        String invoice=normal.get(user,o.orderId()).invoiceId();
        assertThatThrownBy(()->normal.refund(user,invoice,UUID.randomUUID().toString(),"환불"))
                .satisfies(t->assertThat(code(t)).isEqualTo("REFUND_NOT_ALLOWED"));
        assertThat(normal.get(user,o.orderId()).refundStatus()).isNull();
        assertThat(billing.subscription(user).entitlements().canCalculate()).isTrue();
        verify(gateway,never()).cancel(anyString(),anyString(),anyString());
        verify(gateway,never()).cancel(anyString(),anyString(),anyString(),any());
    }

    // #208 환불 제한을 켜도 입금 전 가상계좌 취소와, 켜기 전에 시작된 환불(계좌 재입력)은 그대로 끝까지 처리한다.
    @Test void restrictedPolicyKeepsUnpaidCancelAndInProgressRefund() {
        var started=virtualOrder(true);String startedInvoice=normal.get(user,started.orderId()).invoiceId();
        when(gateway.lookup("key")).thenReturn(va(started.orderId(),"DONE","NONE"));
        assertThat(normal.refund(user,startedInvoice,UUID.randomUUID().toString(),"환불").status()).isEqualTo("ACCOUNT_REQUIRED");

        restrictRefunds();
        when(gateway.cancel(eq("key"),anyString(),anyString(),eq(account()))).thenReturn(va(started.orderId(),"CANCELED","COMPLETED"));
        assertThat(normal.refund(user,startedInvoice,UUID.randomUUID().toString(),"환불",account()).status()).isEqualTo("CANCELED");

        var pending=normal.create(other,UUID.randomUUID().toString(),"seller",BillingPolicy.VERSION,com.ipgo.server.config.RefundPolicyProperties.CONSENT_NO_REFUND);
        when(gateway.confirm(anyString(),anyString(),anyLong(),anyString())).thenReturn(new BillingGateway.Payment("other-key",pending.orderId(),500,"KRW","WAITING_FOR_DEPOSIT","new-mid",null,0,null,"가상계좌","NONE"));
        normal.confirm(other,pending.orderId(),"other-key",500);
        String pendingInvoice=normal.get(other,pending.orderId()).invoiceId();
        when(gateway.lookup("other-key")).thenReturn(new BillingGateway.Payment("other-key",pending.orderId(),500,"KRW","WAITING_FOR_DEPOSIT","new-mid",null,0,null,"가상계좌","NONE"));
        when(gateway.cancel(eq("other-key"),anyString(),anyString())).thenReturn(new BillingGateway.Payment("other-key",pending.orderId(),500,"KRW","CANCELED","new-mid",null,0,now,"가상계좌","NONE"));
        assertThat(normal.refund(other,pendingInvoice,UUID.randomUUID().toString(),"발급 취소").status()).isEqualTo("CANCELED");
    }

    @Test void refundAccountIsRedactedAndOtherOwnersCannotCancel() {
        var o=virtualOrder(true);String inv=normal.get(user,o.orderId()).invoiceId();clearInvocations(gateway);
        assertThatThrownBy(()->normal.refund(other,inv,UUID.randomUUID().toString(),"환불",account())).isInstanceOf(BillingException.class);
        assertThat(account().toString()).doesNotContain("1234567890","합성 예금주");
        assertThat(new com.ipgo.server.dto.request.BillingRequests.CancelPayment("환불",new com.ipgo.server.dto.request.BillingRequests.RefundAccount("88","1234567890","합성 예금주")).toString()).doesNotContain("1234567890");
        verifyNoInteractions(gateway);
    }

    @Test void invalidNestedRefundAccountReturnsNoSensitiveValuesAndCallsNoProvider() throws Exception {
        var o=virtualOrder(true);String inv=normal.get(user,o.orderId()).invoiceId();clearInvocations(gateway);
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/billing/invoices/"+inv+"/cancel-payment")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject(Long.toString(user))))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"reason\":\"환불\",\"refundReceiveAccount\":{\"bank\":\"88\",\"accountNumber\":\"private-fixture-number\",\"holderName\":\"private-fixture-holder\"}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("private-fixture-number","private-fixture-holder");
        verifyNoInteractions(gateway);
    }
    @Test void expiredUnknownRefundErasesAccountAndStopsAutomaticRetries() {
        var o=virtualOrder(true);when(gateway.lookup("key")).thenReturn(va(o.orderId(),"DONE","NONE"));
        when(gateway.cancel(eq("key"),anyString(),anyString(),any())).thenThrow(new BillingGateway.Failure(false,"TIMEOUT"));
        normal.refund(user,normal.get(user,o.orderId()).invoiceId(),UUID.randomUUID().toString(),"환불",account());
        now=now.plus(java.time.Duration.ofDays(15));normal.processOne();
        assertThat(jdbc.queryForObject("select refund_status from billing_normal_orders where id=?",String.class,o.orderId())).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select refund_account_ciphertext from billing_normal_orders where id=?",String.class,o.orderId())).isNull();
        assertThat(jdbc.queryForObject("select refund_account_fingerprint from billing_normal_orders where id=?",String.class,o.orderId())).isNull();
        verify(gateway,times(1)).cancel(eq("key"),anyString(),anyString(),any());
    }

    @Test void merchantRefundRejectionStopsRetriesAndErasesAccount() {
        var o=virtualOrder(true);when(gateway.lookup("key")).thenReturn(va(o.orderId(),"DONE","NONE"));
        when(gateway.cancel(eq("key"),anyString(),anyString(),any())).thenThrow(new BillingGateway.Failure(true,"REFUND_REJECTED"));
        assertThat(normal.refund(user,normal.get(user,o.orderId()).invoiceId(),UUID.randomUUID().toString(),"환불",account()).status()).isEqualTo("REVIEW_REQUIRED");
        now=now.plusSeconds(120);normal.processOne();
        verify(gateway,times(1)).cancel(eq("key"),anyString(),anyString(),any());
        assertThat(jdbc.queryForObject("select refund_account_ciphertext from billing_normal_orders where id=?",String.class,o.orderId())).isNull();
        assertThat(billing.subscription(user).status()).isEqualTo("ACTIVE");
    }

}
