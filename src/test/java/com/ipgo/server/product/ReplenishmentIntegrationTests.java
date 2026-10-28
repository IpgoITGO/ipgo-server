package com.ipgo.server.product;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ipgo.server.DatabaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReplenishmentIntegrationTests extends DatabaseIntegrationTest {
    private static final String DEFAULTS = "/api/replenishment-settings";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM replenishment_settings");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void suggestsValuesUntilSellerConfirmsDefaults() throws Exception {
        long userId = newUser();
        mvc.perform(get(DEFAULTS).with(user(Long.toString(userId))))
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

        mvc.perform(get(DEFAULTS).with(user(Long.toString(userId))))
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
        putJson(userId, DEFAULTS, "{\"leadTimeDays\": 5, \"reviewCycleDays\": 1.5, \"orderUnitQty\": 1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"));
        mvc.perform(put(DEFAULTS).with(user(Long.toString(userId))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadTimeDays\": 5, \"reviewCycleDays\": 29, \"orderUnitQty\": 1}"))
                .andExpect(status().isForbidden());
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

        mvc.perform(get("/api/products").with(user(Long.toString(userId))))
                .andExpect(jsonPath("items[0].unitCost").value(1800))
                .andExpect(jsonPath("items[0].settings.leadTimeDays").value(5))
                .andExpect(jsonPath("items[0].settings.reviewCycleDays").value(29))
                .andExpect(jsonPath("items[0].settings.orderUnitQty").value(12))
                .andExpect(jsonPath("items[0].overridden.length()").value(1))
                .andExpect(jsonPath("items[0].overridden[0]").value("orderUnitQty"));

        // PUT은 보낸 값으로 모두 바꾼다. 비운 칸은 다시 기본값을 따르고, 원가는 보내지 않으면 그대로 둔다.
        putJson(userId, settingsOf(productId), "{\"leadTimeDays\": 3}").andExpect(status().isOk());
        mvc.perform(get("/api/products").with(user(Long.toString(userId))))
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
        mvc.perform(get("/api/products").with(user(Long.toString(userId))))
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

    private ResultActions putJson(long userId, String path, String body) throws Exception {
        return mvc.perform(put(path).with(user(Long.toString(userId))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String settingsOf(long productId) {
        return "/api/products/" + productId + "/replenishment-settings";
    }

    private long registerProduct(long userId) {
        registration.register(userId, warehouses.lockDefault(userId).getId(), List.of(
                new CleanedStockUnit("8800272420016", "케어트릿 120g", "MIX-1", LocalDate.of(2026, 9, 1))));
        return products.findByUserIdAndSkuBarcodeIn(userId, List.of("8800272420016")).getFirst().getId();
    }

    private long newUser() {
        String email = "settings-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
