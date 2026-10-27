package com.ipgo.server.auth;

import java.util.Base64;
import org.junit.jupiter.api.Test;

import static com.ipgo.server.DatabaseIntegrationTest.randomKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalDataCryptoTests {
    private final String encryptionKey = randomKey();
    private final String lookupKey = randomKey();
    private final PersonalDataCrypto crypto = new PersonalDataCrypto(encryptionKey, lookupKey);

    @Test
    void encryptsWithFreshNonceAndRestoresNormalizedEmail() {
        String first = crypto.encryptEmail(" Seller@Example.test ");
        String second = crypto.encryptEmail("seller@example.test");
        String lookup = crypto.emailLookup("seller@example.test");
        assertThat(first).isNotEqualTo(second).doesNotContain("Seller", "Example");
        assertThat(crypto.decryptEmail(first, lookup)).isEqualTo("seller@example.test");
        assertThat(crypto.emailLookup(" SELLER@example.test ")).isEqualTo(lookup);
        assertThat(new PersonalDataCrypto(encryptionKey, randomKey()).emailLookup("seller@example.test"))
                .isNotEqualTo(lookup);
    }

    @Test
    void refusesModifiedCiphertextWrongKeyAndDifferentAccount() {
        String encrypted = crypto.encryptEmail("seller@example.test");
        String lookup = crypto.emailLookup("seller@example.test");
        byte[] damaged = Base64.getDecoder().decode(encrypted.substring(3));
        damaged[damaged.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(damaged);
        assertThatThrownBy(() -> crypto.decryptEmail(tampered, lookup)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PersonalDataCrypto(randomKey(), lookupKey).decryptEmail(encrypted, lookup))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decryptEmail(encrypted, crypto.emailLookup("another@example.test")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesInvalidKeysAndMalformedPayloadsWithoutEchoingValues() {
        for (String key : new String[] {"", "invalid-base64!", Base64.getEncoder().encodeToString(new byte[16])}) {
            assertThatThrownBy(() -> new PersonalDataCrypto(key, lookupKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("IPGO_PII_AES_KEY에 Base64로 인코딩한 32바이트 키가 필요합니다.");
        }
        assertThatThrownBy(() -> new PersonalDataCrypto(encryptionKey, encryptionKey))
                .isInstanceOf(IllegalArgumentException.class);
        for (String payload : new String[] {"v2:abc", "v1:invalid!", "v1:AA=="}) {
            assertThatThrownBy(() -> crypto.decryptEmail(payload, "lookup"))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(payload);
        }
    }
}
