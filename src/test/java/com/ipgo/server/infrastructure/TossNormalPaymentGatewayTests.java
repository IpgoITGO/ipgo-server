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
}
