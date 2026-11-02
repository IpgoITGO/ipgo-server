package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleanedStockUnit;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.service.ProductRegistrationService;
import com.ipgo.server.service.WarehouseService;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// C2 계산 상태. 계산 연동(#27) 전이라 계산 행은 표에 직접 넣는다.
@AutoConfigureMockMvc
class CalculationRunIntegrationTests extends DatabaseIntegrationTest {
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM calculation_results");
        jdbc.update("DELETE FROM calculation_runs");
        jdbc.update("DELETE FROM replenishment_settings");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void showsQueuedRunningAndFailedRuns() throws Exception {
        long userId = newUser();
        long warehouse = warehouseOf(userId);
        long queued = run(warehouse, "QUEUED", null);
        long running = run(warehouse, "RUNNING", null);
        long failed = run(warehouse, "FAILED", "계산 엔진 응답 시간이 지났어요");

        runStatus(userId, queued)
                .andExpect(status().isOk())
                .andExpect(jsonPath("runId").value(queued))
                .andExpect(jsonPath("status").value("QUEUED"))
                .andExpect(jsonPath("asOfDate").value(TODAY.minusDays(2).toString()))
                .andExpect(jsonPath("engineVersion").value("0.3.1"))
                .andExpect(jsonPath("createdAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("completedAt").doesNotExist())
                .andExpect(jsonPath("policy").doesNotExist())
                .andExpect(jsonPath("summary").doesNotExist());
        runStatus(userId, running).andExpect(jsonPath("status").value("RUNNING"));
        runStatus(userId, failed)
                .andExpect(jsonPath("status").value("FAILED"))
                .andExpect(jsonPath("errorMessage").value("계산 엔진 응답 시간이 지났어요"))
                .andExpect(jsonPath("summary").doesNotExist());
    }

    @Test
    void doneRunSummaryMatchesDashboard() throws Exception {
        long userId = newUser();
        long warehouse = warehouseOf(userId);
        long now = product(userId, "8800000000001");
        long soon = product(userId, "8800000000002");
        long later = product(userId, "8800000000003");
        long noData = product(userId, "8800000000004");
        long mismatch = product(userId, "8800000000005");
        long off = product(userId, "8800000000006");
        long run = run(warehouse, "DONE", null);
        result(run, now, "OK", TODAY.plusDays(1));
        result(run, soon, "OK", TODAY.plusDays(3));
        result(run, later, "OK", TODAY.plusDays(30));
        result(run, noData, "NOT_CALCULABLE", null);
        result(run, mismatch, "EXCLUDED", null);
        result(run, off, "OK", TODAY);
        jdbc.update("UPDATE products SET is_active = FALSE WHERE id = ?", off);

        runStatus(userId, run)
                .andExpect(jsonPath("status").value("DONE"))
                .andExpect(jsonPath("completedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("errorMessage").doesNotExist())
                .andExpect(jsonPath("summary.needCount").value(2))
                .andExpect(jsonPath("summary.notNeededCount").value(1))
                .andExpect(jsonPath("summary.notCalculableCount").value(1))
                .andExpect(jsonPath("summary.excludedCount").value(1));
        mvc.perform(get("/api/dashboard/replenishment-needs").with(as(userId)))
                .andExpect(jsonPath("runId").value(run))
                .andExpect(jsonPath("summary.needCount").value(2))
                .andExpect(jsonPath("summary.notNeededCount").value(1));
    }

    @Test
    void checksOwner() throws Exception {
        long owner = newUser();
        long other = newUser();
        long run = run(warehouseOf(owner), "DONE", null);

        runStatus(other, run).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        runStatus(owner, Long.MAX_VALUE).andExpect(status().isNotFound());
    }

    private long run(long warehouseId, String status, String errorMessage) {
        jdbc.update("""
                INSERT INTO calculation_runs (warehouse_id, run_type, as_of_date, status, engine_version,
                    selection_snapshot, error_message, created_at, completed_at)
                VALUES (?, 'CALCULATE', ?, ?, '0.3.1', JSON '{}', ?, ?, ?)
                """, warehouseId, TODAY.minusDays(2), status, errorMessage, Timestamp.from(Instant.now()),
                "DONE".equals(status) || "FAILED".equals(status) ? Timestamp.from(Instant.now()) : null);
        return jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs", Long.class);
    }

    private void result(long runId, long productId, String status, LocalDate shortageOn) {
        jdbc.update("""
                INSERT INTO calculation_results (run_id, product_id, origin_date, start_date, multiplier, horizon_days,
                    expected_shortage_on, status, input_snapshot)
                VALUES (?, ?, ?, ?, 1.000, 30, ?, ?, JSON '{}')
                """, runId, productId, TODAY.minusDays(2), TODAY, shortageOn, status);
    }

    private ResultActions runStatus(long userId, long runId) throws Exception {
        return mvc.perform(get("/api/calculation-runs/" + runId).with(as(userId)));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(long userId) {
        return jwt().jwt(claims -> claims.subject(Long.toString(userId)));
    }

    private long warehouseOf(long userId) {
        return warehouses.lockDefault(userId).getId();
    }

    private long product(long userId, String barcode) {
        registration.register(userId, warehouseOf(userId),
                List.of(new CleanedStockUnit(barcode, "상품 " + barcode, "MIX-" + barcode, LocalDate.of(2026, 9, 1))));
        return products.findByUserIdAndSkuBarcodeIn(userId, List.of(barcode)).getFirst().getId();
    }

    private long newUser() {
        String email = "run-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
