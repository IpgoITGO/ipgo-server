package com.ipgo.server;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.ipgo.server.product.CleanedStockUnit;
import com.ipgo.server.product.ProductRegistrationService;
import com.ipgo.server.product.ProductRepository;
import com.ipgo.server.product.WarehouseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사용자별 접근 제한(BE-02) 점검. 새 API를 추가해도 로그인 없이 열리지 않는지, 다른 회원의 데이터에 닿지 않는지 확인한다.
@AutoConfigureMockMvc
class AccessControlTests extends DatabaseIntegrationTest {
    // 로그인 없이 쓸 수 있는 API. 여기에 새로 넣으려면 명세서와 SecurityConfig를 함께 바꿔야 한다.
    private static final Set<String> PUBLIC = Set.of(
            "GET /api/auth/csrf",
            "GET /api/auth/email-availability",
            "POST /api/auth/signup",
            "POST /api/auth/login",
            "POST /api/auth/refresh",
            "POST /api/auth/logout",
            "POST /api/auth/email-verifications",
            "POST /api/auth/email-verifications/confirm");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM upload_files");
        jdbc.update("DELETE FROM planned_receipts");
        jdbc.update("DELETE FROM replenishment_settings");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void everyApiExceptPublicOnesRequiresLogin() throws Exception {
        Set<String> found = new TreeSet<>();
        for (var info : mappings.getHandlerMethods().keySet()) {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/")) continue;
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    String endpoint = method + " " + path;
                    found.add(endpoint);
                    if (PUBLIC.contains(endpoint)) continue;
                    // 경로 변수는 아무 숫자로 채운다. 로그인 검사가 먼저라 값은 상관없다.
                    mvc.perform(request(HttpMethod.valueOf(method.name()), path.replaceAll("\\{[^}]+}", "1")))
                            .andExpect(result -> assertThat(result.getResponse().getStatus())
                                    .as(endpoint).isEqualTo(401));
                }
            }
        }
        // 공개 API가 실제로 있는지도 확인해, 목록이 낡지 않게 한다.
        assertThat(found).containsAll(PUBLIC);
        assertThat(found).contains("GET /api/auth/me", "POST /api/uploads", "GET /api/products",
                "PUT /api/replenishment-settings", "DELETE /api/planned-receipts/{receiptId}");
    }

    @Test
    void otherUsersCannotChangeOwnersData() throws Exception {
        long owner = newUser();
        long other = newUser();
        long productId = registerProduct(owner);
        long receiptId = createReceipt(owner, productId);

        send(other, HttpMethod.PATCH, "/api/products/" + productId, "{\"skuName\": \"남의 상품\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        send(other, HttpMethod.PUT, "/api/products/" + productId + "/replenishment-settings", "{\"leadTimeDays\": 3}")
                .andExpect(status().isForbidden());
        send(other, HttpMethod.POST, "/api/planned-receipts", "{\"productId\": " + productId + ", \"quantity\": 1}")
                .andExpect(status().isForbidden());
        send(other, HttpMethod.PATCH, "/api/planned-receipts/" + receiptId, "{\"quantity\": 1}")
                .andExpect(status().isForbidden());
        send(other, HttpMethod.DELETE, "/api/planned-receipts/" + receiptId, null)
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT sku_name FROM products WHERE id = ?", String.class, productId))
                .isEqualTo("케어트릿 120g");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_settings", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM planned_receipts", Long.class)).isEqualTo(60L);
        assertThat(jdbc.queryForObject("SELECT status FROM planned_receipts", String.class)).isEqualTo("PENDING");
    }

    @Test
    void listsShowOnlyOwnData() throws Exception {
        long owner = newUser();
        long other = newUser();
        createReceipt(owner, registerProduct(owner));
        send(owner, HttpMethod.PUT, "/api/replenishment-settings",
                "{\"leadTimeDays\": 7, \"reviewCycleDays\": 29, \"orderUnitQty\": 6}").andExpect(status().isOk());

        String asOther = Long.toString(other);
        mvc.perform(get("/api/products").with(jwt().jwt(claims -> claims.subject(asOther)))).andExpect(jsonPath("total").value(0));
        mvc.perform(get("/api/planned-receipts").with(jwt().jwt(claims -> claims.subject(asOther)))).andExpect(jsonPath("total").value(0));
        mvc.perform(get("/api/uploads/periods").with(jwt().jwt(claims -> claims.subject(asOther)))).andExpect(jsonPath("items.length()").value(0));
        mvc.perform(get("/api/replenishment-settings").with(jwt().jwt(claims -> claims.subject(asOther))))
                .andExpect(jsonPath("confirmed").value(false))
                .andExpect(jsonPath("leadTimeDays").value(5));
    }

    private ResultActions send(long userId, HttpMethod method, String path, String body) throws Exception {
        var builder = request(method, path).with(jwt().jwt(claims -> claims.subject(Long.toString(userId))));
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(builder);
    }

    private long createReceipt(long userId, long productId) throws Exception {
        String body = send(userId, HttpMethod.POST, "/api/planned-receipts",
                "{\"productId\": " + productId + ", \"quantity\": 60, \"expectedOn\": \"2026-11-20\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("receiptId").longValue();
    }

    private long registerProduct(long userId) {
        registration.register(userId, warehouses.lockDefault(userId).getId(), List.of(
                new CleanedStockUnit("8800272420016", "케어트릿 120g", "MIX-1", LocalDate.of(2026, 9, 1))));
        return products.findByUserIdAndSkuBarcodeIn(userId, List.of("8800272420016")).getFirst().getId();
    }

    private long newUser() {
        String email = "access-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
