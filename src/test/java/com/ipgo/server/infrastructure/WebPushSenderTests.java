package com.ipgo.server.infrastructure;

import com.ipgo.server.config.PushDeliveryProperties;
import com.ipgo.server.config.PushProperties;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebPushSenderTests {
    @Test void actualLibraryEncryptsAndSignsWithPkcs8KeyWithoutNetwork() throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("ECDSA","BC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var vapid = generator.generateKeyPair();
        var browser = generator.generateKeyPair();
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String publicKey = encoder.encodeToString(((ECPublicKey)vapid.getPublic()).getQ().getEncoded(false));
        var sender = new WebPushSender(new PushProperties(publicKey,List.of("fcm.googleapis.com")),
                new PushDeliveryProperties(true,encoder.encodeToString(vapid.getPrivate().getEncoded()),"https://example.test"));
        var post = sender.prepare("https://fcm.googleapis.com/test-only",
                encoder.encodeToString(((ECPublicKey)browser.getPublic()).getQ().getEncoded(false)),
                encoder.encodeToString(new byte[16]),"{\"notificationId\":\"daily-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(post.getFirstHeader("Authorization").getValue()).startsWith("vapid t=").contains(", k=");
        assertThat(post.getFirstHeader("TTL").getValue()).isEqualTo("3600");
        assertThat(post.getFirstHeader("Content-Encoding").getValue()).isEqualTo("aes128gcm");
        assertThat(new String(post.getEntity().getContent().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("notificationId");
        assertThat(sender.send("http://127.0.0.1/private","x","x",new byte[0]).status()).isEqualTo(400);
        assertThat(sender.send("https://fcm.googleapis.com.evil.test/private","x","x",new byte[0]).status()).isEqualTo(400);
    }
    @Test void retryAfterSupportsSecondsAndHttpDateWithoutRapidRetry() {
        var now=java.time.Instant.parse("2026-11-12T00:00:00Z");
        assertThat(WebPushSender.retryAfter("3600",now)).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(WebPushSender.retryAfter("Thu, 12 Nov 2026 00:10:00 GMT",now)).isEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(WebPushSender.retryAfter("invalid",now)).isEqualTo(java.time.Duration.ofDays(1));
    }
    @Test void enabledSenderFailsAtStartupWithMissingKeyWithoutExposingSecret() {
        assertThatThrownBy(() -> new WebPushSender(new PushProperties("",List.of("fcm.googleapis.com")),
                new PushDeliveryProperties(true,"test-only-secret","https://example.test")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("test-only-secret");
        assertThat(new PushDeliveryProperties(true,"test-only-secret","https://example.test").toString())
                .doesNotContain("test-only-secret");
    }
}
