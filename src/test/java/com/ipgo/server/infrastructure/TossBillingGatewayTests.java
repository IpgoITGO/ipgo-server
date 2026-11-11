package com.ipgo.server.infrastructure;

import com.ipgo.server.config.BillingProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TossBillingGatewayTests {
    private final HttpClient http=mock(HttpClient.class);
    private final JsonMapper mapper=JsonMapper.builder().build();
    private BillingProperties properties(boolean enabled) {
        return new BillingProperties(enabled,false,"TEST","test_sk_fixture_only","security-fixture","","","fixture-mid","http://localhost:5173");
    }
    @SuppressWarnings("unchecked")
    private void response(int status,String body) throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    @Test void disabledGatewayCannotSendAnyRequest() {
        var g=new TossBillingGateway(properties(false),mapper,http);
        assertThatThrownBy(()->g.lookup("order")).isInstanceOf(BillingGateway.Failure.class);
        verifyNoInteractions(http);
    }
    @Test void billingUsesCorrectApiBasicAuthStableIdempotencyAndLongTimeout() throws Exception {
        response(200,"{\"paymentKey\":\"p\",\"orderId\":\"invoice-order\",\"totalAmount\":49000,\"currency\":\"KRW\",\"status\":\"DONE\",\"mId\":\"fixture-mid\"}");
        var g=new TossBillingGateway(properties(true),mapper,http);
        assertThat(g.charge("fixture/key",new BillingGateway.Charge("invoice-order","customer",49000,"monthly"),"fixed-id").status()).isEqualTo("DONE");
        var request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.tosspayments.com/v1/billing/fixture%2Fkey");
        assertThat(request.getValue().headers().firstValue("Idempotency-Key")).contains("fixed-id");
        String auth="Basic "+Base64.getEncoder().encodeToString("test_sk_fixture_only:".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(request.getValue().headers().firstValue("Authorization")).contains(auth);
        assertThat(request.getValue().timeout().orElseThrow().toSeconds()).isGreaterThanOrEqualTo(60);
    }
    @Test void issueUsesAuthKeyEndpointAndLookupUsesOrderEndpoint() throws Exception {
        response(200,"{\"billingKey\":\"fixture\",\"customerKey\":\"customer\",\"card\":{\"number\":\"1234****5678\"}}");
        var g=new TossBillingGateway(properties(true),mapper,http);
        assertThat(g.issue("auth","customer","issue-id").billingKey()).isEqualTo("fixture");
        var r=ArgumentCaptor.forClass(HttpRequest.class); verify(http).send(r.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(r.getValue().uri().getPath()).isEqualTo("/v1/billing/authorizations/issue");
        reset(http); response(200,"{}"); g.lookup("ipgo_order");
        verify(http).send(r.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(r.getValue().uri().getPath()).isEqualTo("/v1/payments/orders/ipgo_order");
    }
    @Test void providerTimeoutAndInvalidJsonNeverBecomeDefiniteDecline() throws Exception {
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new java.net.http.HttpTimeoutException("fixture"));
        var g=new TossBillingGateway(properties(true),mapper,http);
        assertThatThrownBy(()->g.lookup("order")).isInstanceOfSatisfying(BillingGateway.Failure.class,e->assertThat(e.rejected()).isFalse());
        reset(http); response(502,"not-json");
        assertThatThrownBy(()->g.lookup("order")).isInstanceOfSatisfying(BillingGateway.Failure.class,e->assertThat(e.rejected()).isFalse());
    }
    @Test void parsesProviderApprovalOffsetAndRejectsMalformedTime() throws Exception {
        var g=new TossBillingGateway(properties(true),mapper,http);
        response(200,"{\"approvedAt\":\"2026-01-31T10:00:00+09:00\"}");
        assertThat(g.lookup("order").approvedAt()).isEqualTo(java.time.Instant.parse("2026-01-31T01:00:00Z"));
        reset(http); response(200,"{\"approvedAt\":\"invalid\"}");
        assertThatThrownBy(()->g.lookup("order")).isInstanceOfSatisfying(BillingGateway.Failure.class,e->assertThat(e.rejected()).isFalse());
    }
    @Test void knownDeclineIsRetryableButUnknownErrorIsNot() throws Exception {
        var g=new TossBillingGateway(properties(true),mapper,http);
        response(403,"{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"untrusted message\"}");
        assertThatThrownBy(()->g.lookup("order")).isInstanceOfSatisfying(BillingGateway.Failure.class,e->{assertThat(e.rejected()).isTrue();assertThat(e.getMessage()).doesNotContain("untrusted");});
        reset(http); response(500,"{\"code\":\"INTERNAL\"}");
        assertThatThrownBy(()->g.lookup("order")).isInstanceOfSatisfying(BillingGateway.Failure.class,e->assertThat(e.rejected()).isFalse());
    }

    @Test void fullCancelPreservesCaseUsesCancelReasonAndProviderIdempotency() throws Exception {
        response(200,"{\"paymentKey\":\"CaseSensitiveKey\",\"orderId\":\"order\",\"totalAmount\":500,\"balanceAmount\":0,\"currency\":\"KRW\",\"status\":\"CANCELED\",\"mId\":\"fixture-mid\",\"approvedAt\":\"2026-11-11T17:00:00+09:00\",\"cancels\":[{\"canceledAt\":\"2026-11-11T17:01:00+09:00\"}]}");
        var payment=new TossBillingGateway(properties(true),mapper,http).cancel("CaseSensitiveKey","테스트 전액 취소","refund-fixed-id");
        assertThat(payment.status()).isEqualTo("CANCELED");
        assertThat(payment.balanceAmount()).isZero();
        assertThat(payment.canceledAt()).isEqualTo(java.time.Instant.parse("2026-11-11T08:01:00Z"));
        var request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getPath()).isEqualTo("/v1/payments/CaseSensitiveKey/cancel");
        assertThat(request.getValue().headers().firstValue("Idempotency-Key")).contains("refund-fixed-id");
    }
}
