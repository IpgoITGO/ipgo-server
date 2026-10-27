package com.ipgo.server.auth;

import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashTests {
    @Test
    void usesSaltedPbkdf2HmacSha256With600000Iterations() throws Exception {
        var encoder = new SecurityConfig().passwordEncoder();
        String password = "테스트용 비밀번호 example-only";
        String first = encoder.encode(password);
        String second = encoder.encode(password);
        assertThat(first).isNotEqualTo(second).startsWith("{pbkdf2-sha256-600k}");
        byte[] payload = HexFormat.of().parseHex(first.substring(first.indexOf('}') + 1));
        byte[] salt = Arrays.copyOfRange(payload, 0, 16);
        byte[] expectedHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 600_000, 256)).getEncoded();
        assertThat(Arrays.copyOfRange(payload, 16, payload.length)).isEqualTo(expectedHash);
        assertThat(encoder.matches(password, first)).isTrue();
        assertThat(encoder.matches(password + "wrong", first)).isFalse();
    }
}
