package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleanedStockUnit;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.service.ProductRegistrationService;
import com.ipgo.server.service.WarehouseService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReplenishmentIntegrationTests extends DatabaseIntegrationTest {
    private static final String DEFAULTS = "/api/replenishment-settings";
    private static final String HORIZON_GUIDE = "리드타임과 입고 주기를 합쳐 366일 이하로 넣어 주세요.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM planned_receipts");
        jdbc.update("DELETE FROM replenishment_settings");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void suggestsValuesUntilSellerConfirmsDefaults() throws Exception {
        long userId = newUser();
        mvc.perform(get(DEFAULTS).with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("confirmed").value(false))
                .andExpect(jsonPath("leadTimeDays").value(5))
                .andExpect(jsonPath("reviewCycleDays").doesNotExist())
                .andExpect(jsonPath("reviewCycleSource").value("ESTIMATED"))
                .andExpect(jsonPath("orderUnitQty").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE user_id = ?", Integer.class, userId))
                .isZero();

        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 7, \"reviewCycleDays\": 29, \"orderUnitQty\": 6, \"recalculate\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("saved").value(true))
                .andExpect(jsonPath("runId").doesNotExist());

        mvc.perform(get(DEFAULTS).with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(jsonPath("confirmed").value(true))
                .andExpect(jsonPath("leadTimeDays").value(7))
                .andExpect(jsonPath("reviewCycleDays").value(29))
                .andExpect(jsonPath("reviewCycleSource").value("DEFAULT"))
                .andExpect(jsonPath("orderUnitQty").value(6));
    }

    @Test
    void rejectsMissingOrNonPositiveDefaults() throws Exception {
        long userId = newUser();
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5, \"reviewCycleDays\": 29}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("orderUnitQty"));
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 0, \"reviewCycleDays\": 29, \"orderUnitQty\": 1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("leadTimeDays"));
        // 소수 · 글자처럼 형식이 틀린 값도 어느 칸인지 알려준다 (#99).
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5, \"reviewCycleDays\": 1.5, \"orderUnitQty\": 1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("details.field").value("reviewCycleDays"));
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": \"five\", \"reviewCycleDays\": 29, \"orderUnitQty\": 1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("leadTimeDays"));
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5,")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("details").doesNotExist());
        mvc.perform(put(DEFAULTS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadTimeDays\": 5, \"reviewCycleDays\": 29, \"orderUnitQty\": 1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(DEFAULTS)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    void productSettingsOverrideDefaultsAndShowInProductList() throws Exception {
        long userId = newUser();
        long productId = registerProduct(userId);
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5, \"reviewCycleDays\": 29, \"orderUnitQty\": 1}")
                .andExpect(status().isOk());

        putJson(userId, settingsOf(productId), "{\"orderUnitQty\": 12, \"unitCost\": 1800}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("saved").value(true))
                .andExpect(jsonPath("runId").doesNotExist());

        mvc.perform(get("/api/products").with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(jsonPath("items[0].unitCost").value(1800))
                .andExpect(jsonPath("items[0].settings.leadTimeDays").value(5))
                .andExpect(jsonPath("items[0].settings.reviewCycleDays").value(29))
                .andExpect(jsonPath("items[0].settings.orderUnitQty").value(12))
                .andExpect(jsonPath("items[0].overridden.length()").value(1))
                .andExpect(jsonPath("items[0].overridden[0]").value("orderUnitQty"));

        // PUT은 보낸 값으로 모두 바꾼다. 비운 칸은 다시 기본값을 따르고, 원가는 보내지 않으면 그대로 둔다.
        putJson(userId, settingsOf(productId), "{\"leadTimeDays\": 3}").andExpect(status().isOk());
        mvc.perform(get("/api/products").with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(jsonPath("items[0].unitCost").value(1800))
                .andExpect(jsonPath("items[0].settings.leadTimeDays").value(3))
                .andExpect(jsonPath("items[0].settings.orderUnitQty").value(1))
                .andExpect(jsonPath("items[0].overridden[0]").value("leadTimeDays"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_settings WHERE product_id = ?",
                Integer.class, productId)).isEqualTo(1);
    }

    @Test
    void productListUsesSuggestedValuesBeforeDefaultsAreSaved() throws Exception {
        long userId = newUser();
        registerProduct(userId);
        mvc.perform(get("/api/products").with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(jsonPath("items[0].settings.leadTimeDays").value(5))
                .andExpect(jsonPath("items[0].settings.reviewCycleDays").doesNotExist())
                .andExpect(jsonPath("items[0].settings.orderUnitQty").value(1))
                .andExpect(jsonPath("items[0].overridden.length()").value(0));
    }

    @Test
    void rejectsInvalidProductSettingsAndOtherUsersProducts() throws Exception {
        long owner = newUser();
        long other = newUser();
        long productId = registerProduct(owner);

        putJson(owner, settingsOf(productId), "{\"reviewCycleDays\": 0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("reviewCycleDays"));
        putJson(owner, settingsOf(productId), "{\"unitCost\": -1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("unitCost"));
        putJson(other, settingsOf(productId), "{\"leadTimeDays\": 3}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        putJson(owner, settingsOf(Long.MAX_VALUE), "{\"leadTimeDays\": 3}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_settings", Integer.class)).isZero();
    }

    // #257 AI는 리드타임 + 입고 주기가 366일을 넘는 상품이 하나라도 있으면 계산 전체를 실패시킨다.
    @Test
    void rejectsDefaultsWhoseHorizonExceedsEngineLimit() throws Exception {
        long userId = newUser();
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 2, \"reviewCycleDays\": 365, \"orderUnitQty\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("message").value(HORIZON_GUIDE))
                .andExpect(jsonPath("details.field").value("reviewCycleDays"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE user_id = ?", Integer.class, userId))
                .isZero();

        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 1, \"reviewCycleDays\": 365, \"orderUnitQty\": 1}")
                .andExpect(status().isOk());
        assertThat(defaultsOf(userId)).containsExactly(1, 365);
    }

    @Test
    void rejectsProductSettingsWhoseAppliedHorizonExceedsEngineLimit() throws Exception {
        long userId = newUser();
        long productId = registerProduct(userId);
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5, \"reviewCycleDays\": 29, \"orderUnitQty\": 1}")
                .andExpect(status().isOk());

        putJson(userId, settingsOf(productId), "{\"leadTimeDays\": 300, \"reviewCycleDays\": 67}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("message").value(HORIZON_GUIDE))
                .andExpect(jsonPath("details.field").value("reviewCycleDays"));
        assertThat(overrideCount(productId)).isZero();
        putJson(userId, settingsOf(productId), "{\"leadTimeDays\": 300, \"reviewCycleDays\": 66}")
                .andExpect(status().isOk());

        // 비운 칸은 기본값(리드타임 5일 · 입고 주기 29일)을 따르므로 그 값과 합쳐서 본다.
        putJson(userId, settingsOf(productId), "{\"reviewCycleDays\": 362, \"unitCost\": 900}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("reviewCycleDays"));
        putJson(userId, settingsOf(productId), "{\"leadTimeDays\": 338}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("reviewCycleDays"));
        assertThat(jdbc.queryForObject("SELECT lead_time_days FROM replenishment_settings WHERE product_id = ?",
                Integer.class, productId)).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM products WHERE id = ?", Long.class, productId)).isNull();

        putJson(userId, settingsOf(productId), "{\"reviewCycleDays\": 361}").andExpect(status().isOk());
        putJson(userId, settingsOf(productId), "{\"leadTimeDays\": 337}").andExpect(status().isOk());
    }

    @Test
    void checksProductSettingsAgainstSuggestedValuesBeforeDefaultsAreSaved() throws Exception {
        long userId = newUser();
        long productId = registerProduct(userId);
        // 확인 전 리드타임 제안값은 5일이다.
        putJson(userId, settingsOf(productId), "{\"reviewCycleDays\": 362}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("reviewCycleDays"));
        // 입고 주기는 아직 정해지지 않아 가장 작은 1일로 본다. 366일 리드타임은 어떤 입고 주기와도 넘는다.
        putJson(userId, settingsOf(productId), "{\"leadTimeDays\": 366}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("reviewCycleDays"));
        assertThat(overrideCount(productId)).isZero();

        putJson(userId, settingsOf(productId), "{\"reviewCycleDays\": 361}").andExpect(status().isOk());
        putJson(userId, settingsOf(productId), "{\"leadTimeDays\": 365}").andExpect(status().isOk());
        // 저장해 둔 리드타임 365일과 합치면 넘는 기본 입고 주기는 막는다.
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5, \"reviewCycleDays\": 2, \"orderUnitQty\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("message").value(startsWith("'케어트릿 120g' 상품의")));
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5, \"reviewCycleDays\": 1, \"orderUnitQty\": 1}")
                .andExpect(status().isOk());
    }

    @Test
    void rejectsDefaultsThatPushProductOverridesOverEngineLimit() throws Exception {
        long userId = newUser();
        long first = registerProduct(userId, "8800272420016", "케어트릿 120g");
        long second = registerProduct(userId, "8800272420023", "케어트릿 240g");
        long fixed = registerProduct(userId, "8800272420030", "케어트릿 360g");
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5, \"reviewCycleDays\": 29, \"orderUnitQty\": 1}")
                .andExpect(status().isOk());
        putJson(userId, settingsOf(first), "{\"reviewCycleDays\": 300}").andExpect(status().isOk());
        putJson(userId, settingsOf(second), "{\"leadTimeDays\": 300}").andExpect(status().isOk());
        // 두 칸을 모두 정한 상품은 기본값이 바뀌어도 영향이 없다.
        putJson(userId, settingsOf(fixed), "{\"leadTimeDays\": 183, \"reviewCycleDays\": 183}").andExpect(status().isOk());

        // 첫 상품: 기본 리드타임 67 + 상품별 입고 주기 300 = 367
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 67, \"reviewCycleDays\": 29, \"orderUnitQty\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("message").value("'케어트릿 120g' 상품의 상품별 입고 조건과 합치면 366일을 넘어요. " + HORIZON_GUIDE))
                .andExpect(jsonPath("details.field").value("reviewCycleDays"));
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 67, \"reviewCycleDays\": 67, \"orderUnitQty\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("message").value(startsWith("'케어트릿 120g' 외 1개 상품의")));
        assertThat(defaultsOf(userId)).containsExactly(5, 29);

        // 두 상품 모두 합계 366일이면 저장한다.
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 66, \"reviewCycleDays\": 66, \"orderUnitQty\": 1}")
                .andExpect(status().isOk());
        assertThat(defaultsOf(userId)).containsExactly(66, 66);
    }

    private ResultActions putJson(long userId, String path, String body) throws Exception {
        return mvc.perform(put(path).with(jwt().jwt(claims -> claims.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String settingsOf(long productId) {
        return "/api/products/" + productId + "/replenishment-settings";
    }

    private long registerProduct(long userId) {
        return registerProduct(userId, "8800272420016", "케어트릿 120g");
    }

    private long registerProduct(long userId, String barcode, String name) {
        registration.register(userId, warehouses.lockDefault(userId).getId(), List.of(
                new CleanedStockUnit(barcode, name, "MIX-1", LocalDate.of(2026, 9, 1))));
        return products.findByUserIdAndSkuBarcodeIn(userId, List.of(barcode)).getFirst().getId();
    }

    private List<Integer> defaultsOf(long userId) {
        return jdbc.queryForObject("SELECT default_lead_time_days, default_review_cycle_days FROM warehouses WHERE user_id = ?",
                (row, index) -> List.of(row.getInt(1), row.getInt(2)), userId);
    }

    private int overrideCount(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_settings WHERE product_id = ?", Integer.class, productId);
    }

    private long newUser() {
        String email = "settings-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
