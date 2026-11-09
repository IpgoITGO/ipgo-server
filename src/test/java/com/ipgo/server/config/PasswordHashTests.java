package com.ipgo.server.config;

import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashTests {
    @Test
    void acceptsTheD0PythonBootstrapHashWithoutChangingTheEncoder() {
        // Python hashlib.pbkdf2_hmac, 고정 테스트 salt 00..0f, 실제 계정 값 아님.
        String fixture = "{pbkdf2-sha256-600k}000102030405060708090a0b0c0d0e0f"
                + "4d1f17de0a20c248517bf6870dfa67bec6a2ba170111ac2a56a340056eb933d2";
        var encoder = new SecurityConfig().passwordEncoder();
        assertThat(encoder.matches("d0-test-only-password", fixture)).isTrue();
        assertThat(encoder.matches("different-test-password", fixture)).isFalse();
    }

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
