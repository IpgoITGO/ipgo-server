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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ProductIntegrationTests extends DatabaseIntegrationTest {
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;
    @Autowired InventoryUnitRepository inventoryUnits;

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
    void registersEachBarcodeOnceAndSplitsLotsByReceivedDate() {
        long userId = newUser();
        long warehouseId = warehouses.lockDefault(userId).getId();

        RegistrationResult first = registration.register(userId, warehouseId, List.of(
                row("8800000000001", "케어트릿 120g", "MIX-1", SEP_1),
                row("8800000000001", "케어트릿 120g", "MIX-1", SEP_15),
                row("8800000000001", "케어트릿 120g", "MIX-1", SEP_1),
                row("8800000000002", "덴탈껌 S", "MIX-2", SEP_1)));

        assertThat(first).isEqualTo(new RegistrationResult(2, 3));
        Product treat = product(userId, "8800000000001");
        assertThat(inventoryUnits.findByProductId(treat.getId()))
                .extracting(InventoryUnit::getReceivedOn).containsExactlyInAnyOrder(SEP_1, SEP_15);
    }

    @Test
    void laterUploadKeepsSellerEditsAndDoesNotDeactivateMissingProducts() throws Exception {
        long userId = newUser();
        long warehouseId = warehouses.lockDefault(userId).getId();
        registration.register(userId, warehouseId, List.of(
                row("8800000000001", "케어트릿 120g", "MIX-1", SEP_1),
                row("8800000000002", "덴탈껌 S", "MIX-2", SEP_1)));
        Product treat = product(userId, "8800000000001");
        updateProduct(userId, treat.getId(), "{\"skuName\": \"셀러가 고친 이름\"}").andExpect(status().isOk());

        RegistrationResult second = registration.register(userId, warehouseId, List.of(
                row("8800000000001", "엑셀에 적힌 이름", "MIX-1", SEP_1)));

        assertThat(second).isEqualTo(new RegistrationResult(0, 0));
        assertThat(product(userId, "8800000000001").getSkuName()).isEqualTo("셀러가 고친 이름");
        assertThat(product(userId, "8800000000002").isActive()).isTrue();
    }

    @Test
    void sameBarcodeBelongsToEachUserSeparately() {
        long first = newUser();
        long second = newUser();
        registration.register(first, warehouses.lockDefault(first).getId(),
                List.of(row("8800000000001", "케어트릿 120g", "MIX-1", SEP_1)));
        registration.register(second, warehouses.lockDefault(second).getId(),
                List.of(row("8800000000001", "케어트릿 120g", "MIX-1", SEP_1)));

        assertThat(product(first, "8800000000001").getId()).isNotEqualTo(product(second, "8800000000001").getId());
    }

    @Test
    void cannotRegisterIntoAnotherUsersOrMissingWarehouse() {
        long owner = newUser();
        long other = newUser();
        long warehouseId = warehouses.lockDefault(owner).getId();
        var rows = List.of(row("8800000000001", "케어트릿 120g", "MIX-1", SEP_1));

        assertThatThrownBy(() -> registration.register(other, warehouseId, rows))
                .isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
        assertThatThrownBy(() -> registration.register(owner, Long.MAX_VALUE, rows))
                .isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThat(products.count()).isZero();
    }

    @Test
    void listsOwnProductsWithSearchAndPaging() throws Exception {
        long userId = newUser();
        long other = newUser();
        registration.register(userId, warehouses.lockDefault(userId).getId(), List.of(
                row("8800000000001", "Care 트릿 120g", "MIX-1", SEP_1),
                row("8800000000002", "덴탈껌 S", "MIX-2", SEP_1),
                row("8800000000003", "케어 샴푸", "MIX-3", SEP_1)));
        registration.register(other, warehouses.lockDefault(other).getId(),
                List.of(row("8800000000009", "다른 회원 상품", "MIX-9", SEP_1)));

        mvc.perform(get("/api/products").with(user(Long.toString(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("total").value(3))
                .andExpect(jsonPath("page").value(0))
                .andExpect(jsonPath("size").value(20))
                .andExpect(jsonPath("items[0].skuBarcode").value("8800000000001"))
                .andExpect(jsonPath("items[0].productId").isNumber())
                .andExpect(jsonPath("items[0].isActive").value(true))
                .andExpect(jsonPath("items[0].unitCost").doesNotExist());
        mvc.perform(get("/api/products").param("query", "care").with(user(Long.toString(userId))))
                .andExpect(jsonPath("total").value(1))
                .andExpect(jsonPath("items[0].skuName").value("Care 트릿 120g"));
        mvc.perform(get("/api/products").param("query", "0000003").with(user(Long.toString(userId))))
                .andExpect(jsonPath("items[0].skuName").value("케어 샴푸"));
        mvc.perform(get("/api/products").param("page", "1").param("size", "2").with(user(Long.toString(userId))))
                .andExpect(jsonPath("total").value(3))
                .andExpect(jsonPath("items.length()").value(1));
    }

    @Test
    void rejectsInvalidPagingAndAnonymousAccess() throws Exception {
        long userId = newUser();
        for (String[] paging : new String[][] {{"-1", "20"}, {"0", "0"}, {"0", "101"}, {"abc", "20"}}) {
            mvc.perform(get("/api/products").param("page", paging[0]).param("size", paging[1])
                            .with(user(Long.toString(userId))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"));
        }
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void updatesOnlySentFields() throws Exception {
        long userId = newUser();
        registration.register(userId, warehouses.lockDefault(userId).getId(),
                List.of(row("8800000000001", "케어트릿 120g", "MIX-1", SEP_1)));
        long productId = product(userId, "8800000000001").getId();

        updateProduct(userId, productId, "{\"skuName\": \"  케어트릿 150g  \", \"unitCost\": 1800}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("skuName").value("케어트릿 150g"))
                .andExpect(jsonPath("unitCost").value(1800))
                .andExpect(jsonPath("isActive").value(true));
        updateProduct(userId, productId, "{\"isActive\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("skuName").value("케어트릿 150g"))
                .andExpect(jsonPath("unitCost").value(1800))
                .andExpect(jsonPath("isActive").value(false));
        assertThat(product(userId, "8800000000001").isActive()).isFalse();
    }

    @Test
    void rejectsInvalidUpdatesAndOtherUsersProducts() throws Exception {
        long owner = newUser();
        long other = newUser();
        registration.register(owner, warehouses.lockDefault(owner).getId(),
                List.of(row("8800000000001", "케어트릿 120g", "MIX-1", SEP_1)));
        long productId = product(owner, "8800000000001").getId();

        updateProduct(owner, productId, "{\"unitCost\": -1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("unitCost"));
        updateProduct(owner, productId, "{\"skuName\": \"   \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("skuName"));
        updateProduct(other, productId, "{\"skuName\": \"남의 상품\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        updateProduct(owner, Long.MAX_VALUE, "{\"skuName\": \"없음\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        mvc.perform(patch("/api/products/" + productId).with(user(Long.toString(owner)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"skuName\": \"CSRF 없음\"}"))
                .andExpect(status().isForbidden());
        assertThat(product(owner, "8800000000001").getSkuName()).isEqualTo("케어트릿 120g");
    }

    private ResultActions updateProduct(long userId, long productId, String body) throws Exception {
        return mvc.perform(patch("/api/products/" + productId).with(user(Long.toString(userId))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Product product(long userId, String barcode) {
        return products.findByUserIdAndSkuBarcodeIn(userId, List.of(barcode)).getFirst();
    }

    private static CleanedStockUnit row(String barcode, String name, String key, LocalDate receivedOn) {
        return new CleanedStockUnit(barcode, name, key, receivedOn);
    }

    private long newUser() {
        String email = "product-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
