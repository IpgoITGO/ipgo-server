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
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PlannedReceiptIntegrationTests extends DatabaseIntegrationTest {
    private static final String RECEIPTS = "/api/planned-receipts";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM planned_receipts");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void registersReceiptsAndListsThemByExpectedDate() throws Exception {
        long userId = newUser();
        long treat = registerProduct(userId, "8800272420016", "케어트릿 120g");
        long gum = registerProduct(userId, "8800272421003", "덴탈껌 S 10입");

        create(userId, "{\"productId\": " + treat + ", \"quantity\": 120, \"expectedOn\": \"2026-12-01\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("receiptId").isNumber())
                .andExpect(jsonPath("reflected").value(true))
                .andExpect(jsonPath("runId").doesNotExist());
        create(userId, "{\"productId\": " + gum + ", \"quantity\": 60}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("reflected").value(false));
        create(userId, "{\"productId\": " + gum + ", \"quantity\": 30, \"expectedOn\": \"2026-11-05\"}")
                .andExpect(status().isCreated());

        mvc.perform(get(RECEIPTS).with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("total").value(3))
                .andExpect(jsonPath("items[0].expectedOn").value("2026-11-05"))
                .andExpect(jsonPath("items[0].skuName").value("덴탈껌 S 10입"))
                .andExpect(jsonPath("items[0].status").value("PENDING"))
                .andExpect(jsonPath("items[1].productId").value(treat))
                .andExpect(jsonPath("items[1].quantity").value(120))
                .andExpect(jsonPath("items[1].receivedOn").doesNotExist())
                .andExpect(jsonPath("items[2].status").value("NO_DATE"))
                .andExpect(jsonPath("items[2].reflected").value(false))
                .andExpect(jsonPath("items[2].expectedOn").doesNotExist());
    }

    @Test
    void listIsEmptyBeforeFirstReceiptAndRequiresLogin() throws Exception {
        long userId = newUser();
        mvc.perform(get(RECEIPTS).with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("total").value(0))
                .andExpect(jsonPath("items.length()").value(0));
        mvc.perform(get(RECEIPTS)).andExpect(status().isUnauthorized());
    }

    @Test
    void addingDateLaterMakesReceiptReflected() throws Exception {
        long userId = newUser();
        long product = registerProduct(userId, "8800272420016", "케어트릿 120g");
        long receiptId = idOf(create(userId, "{\"productId\": " + product + ", \"quantity\": 60}"));

        update(userId, receiptId, "{\"expectedOn\": \"2026-11-20\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("status").value("PENDING"))
                .andExpect(jsonPath("reflected").value(true))
                .andExpect(jsonPath("quantity").value(60))
                .andExpect(jsonPath("expectedOn").value("2026-11-20"));
        update(userId, receiptId, "{\"quantity\": 72}")
                .andExpect(jsonPath("quantity").value(72))
                .andExpect(jsonPath("expectedOn").value("2026-11-20"));
    }

    @Test
    void cancelledReceiptLeavesListButStaysInDatabase() throws Exception {
        long userId = newUser();
        long product = registerProduct(userId, "8800272420016", "케어트릿 120g");
        long receiptId = idOf(create(userId, "{\"productId\": " + product + ", \"quantity\": 60, \"expectedOn\": \"2026-11-20\"}"));

        mvc.perform(delete(RECEIPTS + "/" + receiptId).with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(status().isNoContent());

        mvc.perform(get(RECEIPTS).with(jwt().jwt(claims -> claims.subject(Long.toString(userId))))).andExpect(jsonPath("total").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM planned_receipts WHERE id = ?", String.class, receiptId))
                .isEqualTo("CANCELLED");
        update(userId, receiptId, "{\"quantity\": 1}").andExpect(status().isNotFound());
        mvc.perform(delete(RECEIPTS + "/" + receiptId).with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        long userId = newUser();
        long product = registerProduct(userId, "8800272420016", "케어트릿 120g");

        create(userId, "{\"quantity\": 60}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("productId"));
        create(userId, "{\"productId\": " + product + ", \"quantity\": 0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("quantity"));
        create(userId, "{\"productId\": " + product + ", \"quantity\": 1.5}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"));
        create(userId, "{\"productId\": " + product + ", \"quantity\": 10, \"expectedOn\": \"2026-13-01\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"));
        mvc.perform(post(RECEIPTS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": " + product + ", \"quantity\": 10}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM planned_receipts", Integer.class)).isZero();
    }

    @Test
    void cannotTouchOtherUsersProductsOrReceipts() throws Exception {
        long owner = newUser();
        long other = newUser();
        long product = registerProduct(owner, "8800272420016", "케어트릿 120g");
        long receiptId = idOf(create(owner, "{\"productId\": " + product + ", \"quantity\": 60, \"expectedOn\": \"2026-11-20\"}"));

        create(other, "{\"productId\": " + product + ", \"quantity\": 10}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        create(owner, "{\"productId\": " + Long.MAX_VALUE + ", \"quantity\": 10}")
                .andExpect(status().isNotFound());
        update(other, receiptId, "{\"quantity\": 1}").andExpect(status().isForbidden());
        mvc.perform(delete(RECEIPTS + "/" + receiptId).with(jwt().jwt(claims -> claims.subject(Long.toString(other)))))
                .andExpect(status().isForbidden());
        mvc.perform(get(RECEIPTS).with(jwt().jwt(claims -> claims.subject(Long.toString(other))))).andExpect(jsonPath("total").value(0));
        update(owner, Long.MAX_VALUE, "{\"quantity\": 1}").andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT quantity FROM planned_receipts WHERE id = ?", Long.class, receiptId))
                .isEqualTo(60L);
    }

    private ResultActions create(long userId, String body) throws Exception {
        return mvc.perform(post(RECEIPTS).with(jwt().jwt(claims -> claims.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(long userId, long receiptId, String body) throws Exception {
        return mvc.perform(patch(RECEIPTS + "/" + receiptId).with(jwt().jwt(claims -> claims.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long idOf(ResultActions created) throws Exception {
        return mapper.readTree(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("receiptId").longValue();
    }

    private long registerProduct(long userId, String barcode, String name) {
        registration.register(userId, warehouses.lockDefault(userId).getId(),
                List.of(new CleanedStockUnit(barcode, name, "MIX-" + barcode, LocalDate.of(2026, 9, 1))));
        return products.findByUserIdAndSkuBarcodeIn(userId, List.of(barcode)).getFirst().getId();
    }

    private long newUser() {
        String email = "receipt-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
