package com.ipgo.server.infrastructure;

import com.ipgo.server.config.NormalPaymentProperties;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TossNormalPaymentGatewayTests {
    @Test void checkoutUsesConfirmAndPaymentKeyLookupInsteadOfUnsupportedOrderLookup() throws Exception {
        var http=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn("{}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var g=new TossNormalPaymentGateway(new NormalPaymentProperties(true,"test_gsk_fixture_only",""),JsonMapper.builder().build(),http);
        g.confirm("CaseKey","order",500,"fixed-provider-key");
        var request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getPath()).isEqualTo("/v1/payments/confirm");
        assertThat(request.getValue().headers().firstValue("Idempotency-Key")).contains("fixed-provider-key");
        assertThat(request.getValue().timeout().orElseThrow().toSeconds()).isGreaterThanOrEqualTo(60);
        clearInvocations(http);
        g.lookup("CaseKey"); verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getPath()).isEqualTo("/v1/payments/CaseKey");
    }
    @Test void liveOrIndividualSecretCannotEnableCheckout() {
        for(String key:new String[]{"live_gsk_fixture","test_sk_fixture",""})
            assertThatThrownBy(()->new NormalPaymentProperties(true,key,"")).isInstanceOf(IllegalStateException.class);
        var http=mock(HttpClient.class);
        var g=new TossNormalPaymentGateway(new NormalPaymentProperties(false,"",""),JsonMapper.builder().build(),http);
        assertThatThrownBy(()->g.lookup("key")).isInstanceOf(BillingGateway.Failure.class);
        verifyNoInteractions(http);
    }
    @Test void virtualRefundSendsAccountAndParsesBankRefundState() throws Exception {
        var http=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"method\":\"가상계좌\",\"status\":\"CANCELED\",\"virtualAccount\":{\"refundStatus\":\"PENDING\"},\"cancels\":[{\"canceledAt\":\"2026-01-01T00:00:00+09:00\"}]}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var mapper=JsonMapper.builder().build();var g=new TossNormalPaymentGateway(new NormalPaymentProperties(true,"test_gsk_fixture_only",""),mapper,http);
        var result=g.cancel("fixture-key","합성 환불","same-key",new NormalPaymentGateway.RefundAccount("88","1234567890","합성 예금주"));
        var request=ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        var subscriber=HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
        request.getValue().bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
            public void onNext(java.nio.ByteBuffer buffer) { subscriber.onNext(java.util.List.of(buffer)); }
            public void onError(Throwable error) { subscriber.onError(error); }
            public void onComplete() { subscriber.onComplete(); }
        });
        var body=mapper.readTree(subscriber.getBody().toCompletableFuture().join());
        assertThat(body.path("refundReceiveAccount").path("bank").asText()).isEqualTo("88");
        assertThat(body.path("refundReceiveAccount").path("accountNumber").asText()).isEqualTo("1234567890");
        assertThat(body.path("refundReceiveAccount").path("holderName").asText()).isEqualTo("합성 예금주");
        assertThat(result.method()).isEqualTo("가상계좌");assertThat(result.virtualRefundStatus()).isEqualTo("PENDING");
    }
    @Test void explicitAccountRejectionDoesNotBecomeAmbiguousTimeout() throws Exception {
        var http=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);

        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var g=new TossNormalPaymentGateway(new NormalPaymentProperties(true,"test_gsk_fixture_only",""),JsonMapper.builder().build(),http);
        for(String code:java.util.List.of("INVALID_REFUND_ACCOUNT_INFO","NOT_ALLOWED_REFUND_BANK")) {
            when(response.statusCode()).thenReturn(code.equals("NOT_ALLOWED_REFUND_BANK")?403:400);
            when(response.body()).thenReturn("{\"code\":\""+code+"\"}");
            assertThatThrownBy(()->g.cancel("fixture-key","환불","same-key",new NormalPaymentGateway.RefundAccount("88","1234567890","합성 예금주")))
            .isInstanceOfSatisfying(BillingGateway.Failure.class,e->{ assertThat(e.rejected()).isTrue(); assertThat(e.code()).isEqualTo("REFUND_ACCOUNT_INVALID"); });
        }
    }

    @Test void merchantRefundRestrictionIsDefinitiveAndContainsNoProviderPayload() throws Exception {
        var http=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);when(response.body()).thenReturn("{\"code\":\"NOT_SUPPORTED_REFUND\",\"message\":\"private-provider-message\"}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var g=new TossNormalPaymentGateway(new NormalPaymentProperties(true,"test_gsk_fixture_only",""),JsonMapper.builder().build(),http);
        assertThatThrownBy(()->g.cancel("fixture-key","환불","same-key",new NormalPaymentGateway.RefundAccount("88","1234567890","합성 예금주")))
            .isInstanceOfSatisfying(BillingGateway.Failure.class,e->{assertThat(e.rejected()).isTrue();assertThat(e.code()).isEqualTo("REFUND_REJECTED");assertThat(e.getMessage()).doesNotContain("private-provider-message");});
    }

}
