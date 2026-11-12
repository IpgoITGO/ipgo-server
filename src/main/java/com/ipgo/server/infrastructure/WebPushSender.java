package com.ipgo.server.infrastructure;

import com.ipgo.server.config.PushDeliveryProperties;
import com.ipgo.server.config.PushProperties;
import java.net.URI;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.Security;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Utils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(PushDeliveryProperties.class)
public class WebPushSender implements PushSender {
    private final PushProperties push;
    private final PushDeliveryProperties delivery;
    private final PushService service;

    public WebPushSender(PushProperties push, PushDeliveryProperties delivery) {
        this.push = push;
        this.delivery = delivery;
        if (!delivery.enabled()) { service = null; return; }
        try {
            if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
            var privateKey = KeyFactory.getInstance("ECDSA", "BC").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(delivery.privateKey())));
            service = new PushService(new KeyPair(Utils.loadPublicKey(push.publicKey()), privateKey), delivery.subject());
            if (!delivery.subject().startsWith("https://") && !delivery.subject().startsWith("mailto:")) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new IllegalStateException("Enabled Web Push requires a valid VAPID key pair and subject");
        }
    }

    org.apache.http.client.methods.HttpPost prepare(String endpoint, String p256dh, String auth, byte[] payload) throws Exception {
        return service.preparePost(new Notification(endpoint, p256dh, auth, payload, 3600), Encoding.AES128GCM);
    }

    static java.time.Duration retryAfter(String header, java.time.Instant now) {
        if (header == null) return java.time.Duration.ZERO;
        try {
            long seconds;
            if (header.matches("[0-9]+")) seconds = Long.parseLong(header);
            else seconds = java.time.Duration.between(now, java.time.ZonedDateTime.parse(header,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            return java.time.Duration.ofSeconds(Math.min(86400, Math.max(0, seconds)));
        } catch (RuntimeException invalid) { return java.time.Duration.ofDays(1); }
    }

    @Override public Result send(String endpoint, String p256dh, String auth, byte[] payload) {
        if (!delivery.enabled()) return new Result(-1);
        try {
            // 저장된 주소도 발송 직전 재검증. 리다이렉트/HTTP 라이브러리 재시도로 SSRF나 중복을 만들지 않는다.
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || push.allowedHosts().stream().noneMatch(rule -> rule.startsWith(".")
                            ? host.endsWith(rule) : host.equals(rule))) return new Result(400);
            var post = prepare(endpoint, p256dh, auth, payload);
            var config = RequestConfig.custom().setConnectTimeout(4000).setSocketTimeout(8000)
                    .setConnectionRequestTimeout(4000).build();
            try (var client = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
                    .setDefaultRequestConfig(config).build(); var response = client.execute(post)) {
                var retry = response.getFirstHeader("Retry-After");
                return new Result(response.getStatusLine().getStatusCode(), retryAfter(retry == null ? null : retry.getValue(), java.time.Instant.now()));
            }
        } catch (Exception exception) {
            // exception message/HTTP response에는 endpoint·키가 들어갈 수 있어 출력하지 않는다.
            return new Result(-1);
        }
    }
}
