package com.ipgo.server.config;

import java.net.URI;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({BillingProperties.class,NormalPaymentProperties.class,RefundPolicyProperties.class})
public class BillingConfig {
    public BillingConfig(BillingProperties p) {
        if (!"TEST".equals(p.mode())) throw new IllegalStateException("Billing supports TEST mode only");
        if (p.workerEnabled() && !p.enabled()) throw new IllegalStateException("Billing worker requires billing API");
        if (!p.enabled()) return;
        if (!p.secretKey().startsWith("test_sk_") || p.merchantId().isBlank() || p.securityKey().isBlank())
            throw new IllegalStateException("Test individual API credentials and merchant identity required");
        for (String key : new String[]{p.encryptionKey(),p.lookupKey()}) {
            try { if (Base64.getDecoder().decode(key).length != 32) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("Billing encryption/lookup keys must be 32 bytes"); }
        }
        if (java.security.MessageDigest.isEqual(Base64.getDecoder().decode(p.encryptionKey()),Base64.getDecoder().decode(p.lookupKey()))) throw new IllegalStateException("Separate billing storage keys required");
        URI u=URI.create(p.returnOrigin());
        if (u.getHost()==null || u.getRawQuery()!=null || u.getFragment()!=null || u.getUserInfo()!=null
                || (u.getPath()!=null && !u.getPath().isEmpty())
                || !("https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && "localhost".equals(u.getHost()))))
            throw new IllegalStateException("Billing return origin must be HTTPS or local development origin");
    }
}
