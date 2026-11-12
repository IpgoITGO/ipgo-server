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
            String sql=new org.springframework.core.io.ClassPathResource("db/migration/V27__create_normal_payment_orders.sql").getContentAsString(StandardCharsets.UTF_8).split("-- PostgreSQL 전용")[0];
            for(String s:sql.split(";")) if(!s.isBlank()) jdbc.execute(s);
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

}
