package com.ipgo.server;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
public abstract class DatabaseIntegrationTest {
    private static final String JWT_SECRET;
    static {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        JWT_SECRET = java.util.Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:ipgo-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("ipgo.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("ipgo.auth.cookie-secure", () -> "false");
    }
}
