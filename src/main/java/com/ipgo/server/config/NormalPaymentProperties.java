package com.ipgo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

@ConfigurationProperties("ipgo.payment")
public record NormalPaymentProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String secretKey, @DefaultValue("") String merchantId) {
    public NormalPaymentProperties {
        if(enabled && !secretKey.startsWith("test_gsk_"))
            throw new IllegalStateException("Normal payments require a TEST checkout secret key");
    }
    @Override public String toString() { return "NormalPaymentProperties[redacted]"; }
}
