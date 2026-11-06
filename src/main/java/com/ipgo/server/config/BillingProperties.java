package com.ipgo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ipgo.billing")
public record BillingProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean workerEnabled,
        @DefaultValue("TEST") String mode,
        @DefaultValue("") String secretKey, @DefaultValue("") String securityKey,
        @DefaultValue("") String encryptionKey, @DefaultValue("") String lookupKey,
        @DefaultValue("") String merchantId,
        @DefaultValue("http://localhost:5173") String returnOrigin) {
    @Override public String toString() { return "BillingProperties[redacted]"; }
}
