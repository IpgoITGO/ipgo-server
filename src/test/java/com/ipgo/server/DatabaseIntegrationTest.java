package com.ipgo.server;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
public abstract class DatabaseIntegrationTest {
    private static final String AES_KEY = randomKey();
    private static final String LOOKUP_KEY = randomKey();

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:ipgo-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("IPGO_PII_AES_KEY", () -> AES_KEY);
        registry.add("IPGO_PII_LOOKUP_KEY", () -> LOOKUP_KEY);
    }

    public static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
