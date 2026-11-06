package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.support.UploadTestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #111 보관 요율 · 상품 상자 크기 저장
@AutoConfigureMockMvc
class StorageCostIntegrationTests extends DatabaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void schema() { UploadTestDatabase.storageCostSchema(jdbc); }

    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM product_boxes");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void emptyBeforeAnythingIsSaved() throws Exception {
        long owner = newUser();
        mvc.perform(get("/api/settings/storage-cost").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("ratePerCbm").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("boxes").isEmpty());
    }

    @Test
    void savesAndClearsRate() throws Exception {
        long owner = newUser();
        putJson(owner, "/api/settings/storage-cost", "{\"ratePerCbm\": 15000.5}")
                .andExpect(status().isOk()).andExpect(jsonPath("ratePerCbm").value(15000.5));
        mvc.perform(get("/api/settings/storage-cost").with(as(owner))).andExpect(jsonPath("ratePerCbm").value(15000.5));
        putJson(owner, "/api/settings/storage-cost", "{\"ratePerCbm\": null}")
                .andExpect(status().isOk()).andExpect(jsonPath("ratePerCbm").value(org.hamcrest.Matchers.nullValue()));
        // Storage cost is not a calculation input, so saving it never starts a calculation.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_runs", Integer.class)).isZero();
    }

    @Test
    void rejectsNonPositiveOrTooPreciseValues() throws Exception {
        long owner = newUser();
        long product = product(owner, "BOX-1");
        putJson(owner, "/api/settings/storage-cost", "{\"ratePerCbm\": 0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"))
                .andExpect(jsonPath("details.field").value("ratePerCbm"));
        putJson(owner, "/api/settings/storage-cost", "{\"ratePerCbm\": 1.234}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("ratePerCbm"));
        putJson(owner, "/api/products/" + product + "/box", "{\"width\": 30, \"depth\": -1, \"height\": 10}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("depth"));
        putJson(owner, "/api/products/" + product + "/box", "{\"width\": 30, \"depth\": 20}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("height"));
        putJson(owner, "/api/products/" + product + "/box", "{\"width\": \"넓음\", \"depth\": 20, \"height\": 10}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("width"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_boxes", Integer.class)).isZero();
    }

    @Test
    void savesUpdatesAndDeletesBoxForOwnProductOnly() throws Exception {
        long owner = newUser();
        long other = newUser();
        long first = product(owner, "BOX-1");
        long second = product(owner, "BOX-2");
        long foreign = product(other, "BOX-3");

        putJson(owner, "/api/products/" + second + "/box", "{\"width\": 30.5, \"depth\": 20, \"height\": 10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("productId").value(second))
                .andExpect(jsonPath("width").value(30.5))
                .andExpect(jsonPath("height").value(10.0));
        putJson(owner, "/api/products/" + first + "/box", "{\"width\": 10, \"depth\": 10, \"height\": 10}").andExpect(status().isOk());
        putJson(owner, "/api/products/" + second + "/box", "{\"width\": 30.5, \"depth\": 20, \"height\": 12.5}").andExpect(status().isOk());
        putJson(other, "/api/products/" + foreign + "/box", "{\"width\": 1, \"depth\": 1, \"height\": 1}").andExpect(status().isOk());

        mvc.perform(get("/api/settings/storage-cost").with(as(owner)))
                .andExpect(jsonPath("boxes.length()").value(2))
                .andExpect(jsonPath("boxes[0].productId").value(first))
                .andExpect(jsonPath("boxes[1].productId").value(second))
                .andExpect(jsonPath("boxes[1].height").value(12.5));

        putJson(other, "/api/products/" + first + "/box", "{\"width\": 1, \"depth\": 1, \"height\": 1}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        mvc.perform(delete("/api/products/" + first + "/box").with(as(other))).andExpect(status().isForbidden());
        putJson(owner, "/api/products/" + Long.MAX_VALUE + "/box", "{\"width\": 1, \"depth\": 1, \"height\": 1}")
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/products/" + first + "/box").with(as(owner))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/products/" + first + "/box").with(as(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/settings/storage-cost").with(as(owner)))
                .andExpect(jsonPath("boxes.length()").value(1))
                .andExpect(jsonPath("boxes[0].productId").value(second));
        // The other member's box is untouched by the owner's changes.
        assertThat(jdbc.queryForObject("SELECT width_cm FROM product_boxes WHERE product_id = ?", Double.class, foreign))
                .isEqualTo(1.0);
    }

    private ResultActions putJson(long userId, String path, String body) throws Exception {
        return mvc.perform(put(path).with(as(userId)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static RequestPostProcessor as(long userId) {
        return jwt().jwt(claims -> claims.subject(Long.toString(userId)));
    }

    private long newUser() {
        return UploadTestDatabase.newUser(jdbc, "storage-" + UUID.randomUUID() + "@example.test");
    }

    private long product(long userId, String barcode) {
        jdbc.update("INSERT INTO products (user_id, sku_barcode, sku_name, is_active) VALUES (?, ?, ?, TRUE)", userId, barcode, barcode);
        return jdbc.queryForObject("SELECT id FROM products WHERE user_id = ? AND sku_barcode = ?", Long.class, userId, barcode);
    }
}
