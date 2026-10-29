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

    // 기본은 H2 메모리 DB. IPGO_TEST_DB_URL을 주면 그 DB(예: 로컬 PostgreSQL)로 같은 테스트를 돌린다.
    // 실제 DB는 테스트가 행을 만들고 지우므로 빈 검증용 DB만 쓴다.
    private static final String TEST_DB_URL = System.getenv("IPGO_TEST_DB_URL");

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        if (TEST_DB_URL == null || TEST_DB_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:ipgo-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
        } else {
            registry.add("spring.datasource.url", () -> TEST_DB_URL);
            registry.add("spring.datasource.username", () -> System.getenv("IPGO_TEST_DB_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv("IPGO_TEST_DB_PASSWORD"));
        }
        registry.add("ipgo.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("ipgo.auth.cookie-secure", () -> "false");
    }
}
