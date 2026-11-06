package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.support.UploadTestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #119 내 입고량 저장 · 조회 · 되돌리기
@AutoConfigureMockMvc
class PlannedQuantityIntegrationTests extends DatabaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void schema() { UploadTestDatabase.plannedQuantitySchema(jdbc); }

    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM product_planned_quantities");
        jdbc.update("DELETE FROM calculation_runs");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void emptyBeforeAnythingIsDecided() throws Exception {
        long owner = newUser();
        mvc.perform(get("/api/planned-quantities").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("items").isEmpty());
    }

    @Test
    void savesReplacesAndDeletesWithoutTouchingNewCalculations() throws Exception {
        long owner = newUser();
        long first = product(owner, "P-1");
        long second = product(owner, "P-2");
        long run = run(owner, "DONE");

        save(owner, second, 40, "SCENARIO_120", run)
                .andExpect(status().isOk())
                .andExpect(jsonPath("productId").value(second))
                .andExpect(jsonPath("quantity").value(40))
                .andExpect(jsonPath("source").value("SCENARIO_120"))
                .andExpect(jsonPath("calculationRunId").value(run))
                .andExpect(jsonPath("decidedAt").value(endsWith("+09:00")));
        save(owner, first, 10, "MANUAL", run).andExpect(status().isOk());
        save(owner, second, 35, "MANUAL", run).andExpect(status().isOk()).andExpect(jsonPath("source").value("MANUAL"));

        // A newer calculation keeps the decision; the client compares calculationRunId with the current run.
        long newer = run(owner, "DONE");
        mvc.perform(get("/api/planned-quantities").with(as(owner)))
                .andExpect(jsonPath("items.length()").value(2))
                .andExpect(jsonPath("items[0].productId").value(first))
                .andExpect(jsonPath("items[1].productId").value(second))
                .andExpect(jsonPath("items[1].quantity").value(35))
                .andExpect(jsonPath("items[1].calculationRunId").value(run));
        save(owner, second, 50, "SCENARIO_100", newer).andExpect(jsonPath("calculationRunId").value(newer));

        mvc.perform(delete("/api/products/" + first + "/planned-quantity").with(as(owner))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/products/" + first + "/planned-quantity").with(as(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/planned-quantities").with(as(owner)))
                .andExpect(jsonPath("items.length()").value(1))
                .andExpect(jsonPath("items[0].productId").value(second));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_runs", Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsInvalidInputAndRunsThatAreNotDoneOrMissing() throws Exception {
        long owner = newUser();
        long product = product(owner, "P-1");
        long done = run(owner, "DONE");
        long queued = run(owner, "QUEUED");

        save(owner, product, 0, "MANUAL", done).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT")).andExpect(jsonPath("details.field").value("quantity"));
        putJson(owner, product, "{\"quantity\": 1.5, \"source\": \"MANUAL\", \"calculationRunId\": " + done + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("quantity"));
        save(owner, product, 5, "GUESS", done).andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("source"));
        putJson(owner, product, "{\"quantity\": 5, \"source\": \"MANUAL\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("details.field").value("calculationRunId"));
        save(owner, product, 5, "MANUAL", queued).andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("calculationRunId"));
        save(owner, product, 5, "MANUAL", Long.MAX_VALUE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("details.field").value("calculationRunId"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_planned_quantities", Integer.class)).isZero();
    }

    @Test
    void otherMembersCannotReadOrChangeDecisions() throws Exception {
        long owner = newUser();
        long other = newUser();
        long product = product(owner, "P-1");
        long run = run(owner, "DONE");
        long otherProduct = product(other, "P-9");
        long otherRun = run(other, "DONE");
        save(owner, product, 40, "SCENARIO_80", run).andExpect(status().isOk());

        save(other, product, 1, "MANUAL", otherRun).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        mvc.perform(delete("/api/products/" + product + "/planned-quantity").with(as(other))).andExpect(status().isForbidden());
        // Someone else's calculation cannot be used as the basis either.
        save(other, otherProduct, 1, "MANUAL", run).andExpect(status().isForbidden());
        save(owner, Long.MAX_VALUE, 1, "MANUAL", run).andExpect(status().isNotFound());
        mvc.perform(get("/api/planned-quantities").with(as(other))).andExpect(jsonPath("items").isEmpty());
        assertThat(jdbc.queryForObject("SELECT quantity FROM product_planned_quantities WHERE product_id = ?", Long.class, product))
                .isEqualTo(40L);
    }

    private ResultActions save(long userId, long productId, long quantity, String source, long runId) throws Exception {
        return putJson(userId, productId, "{\"quantity\": " + quantity + ", \"source\": \"" + source
                + "\", \"calculationRunId\": " + runId + "}");
    }

    private ResultActions putJson(long userId, long productId, String body) throws Exception {
        return mvc.perform(put("/api/products/" + productId + "/planned-quantity").with(as(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static RequestPostProcessor as(long userId) {
        return jwt().jwt(claims -> claims.subject(Long.toString(userId)));
    }

    private long newUser() {
        return UploadTestDatabase.newUser(jdbc, "planned-" + UUID.randomUUID() + "@example.test");
    }

    private long warehouse(long userId) {
        var ids = jdbc.queryForList("SELECT id FROM warehouses WHERE user_id = ?", Long.class, userId);
        if (!ids.isEmpty()) return ids.getFirst();
        jdbc.update("INSERT INTO warehouses (user_id, name) VALUES (?, '기본 창고')", userId);
        return jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id = ?", Long.class, userId);
    }

    private long product(long userId, String barcode) {
        jdbc.update("INSERT INTO products (user_id, sku_barcode, sku_name, is_active) VALUES (?, ?, ?, TRUE)", userId, barcode, barcode);
        return jdbc.queryForObject("SELECT id FROM products WHERE user_id = ? AND sku_barcode = ?", Long.class, userId, barcode);
    }

    private long run(long userId, String status) {
        jdbc.update("""
                INSERT INTO calculation_runs (warehouse_id, run_type, as_of_date, status, engine_version, selection_snapshot, created_at)
                VALUES (?, 'CALCULATE', ?, ?, 'test', JSON '{}', ?)
                """, warehouse(userId), LocalDate.of(2026, 10, 31), status, Timestamp.from(Instant.now()));
        return jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs", Long.class);
    }
}
