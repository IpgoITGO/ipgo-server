package com.ipgo.server.billing;

import com.ipgo.server.util.BillingPolicy;
import com.ipgo.server.config.BillingConfig;
import com.ipgo.server.config.BillingProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BillingPolicyTests {
    @Test void leapYearAndOriginalAnchorArePreserved() {
        Instant a=Instant.parse("2024-01-31T01:00:00Z");
        assertThat(BillingPolicy.boundary(a,1)).isEqualTo(Instant.parse("2024-02-29T01:00:00Z"));
        assertThat(BillingPolicy.boundary(a,2)).isEqualTo(Instant.parse("2024-03-31T01:00:00Z"));
    }
    @Test void reviewStorageKeysMustBeDifferentDecodedBytes() {
        String a=java.util.Base64.getEncoder().encodeToString(new byte[32]);
        String b=java.util.Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        assertThatThrownBy(()->new BillingConfig(new BillingProperties(true,false,"TEST","test_sk_fixture","fixture",a,b,"fixture-mid","http://localhost:5173")))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void disabledRequiresNoSecretsAndLiveIsRejected() {
        new BillingConfig(new BillingProperties(false,false,"TEST","","","","","","http://localhost:5173"));
        assertThatThrownBy(()->new BillingConfig(new BillingProperties(false,false,"LIVE","","","","","","http://localhost:5173")))
                .isInstanceOf(IllegalStateException.class);
    }
}
