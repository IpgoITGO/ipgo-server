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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// D1 입고 필요 목록과 K1 SKU 상세. 계산 연동 전이라 계산 결과는 표에 직접 넣는다.
@AutoConfigureMockMvc
class DashboardIntegrationTests extends DatabaseIntegrationTest {
    private static final String NEEDS = "/api/dashboard/replenishment-needs";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM ai_explanations");
        jdbc.update("DELETE FROM backtest_metrics");
        jdbc.update("DELETE FROM scenario_results");
        jdbc.update("DELETE FROM run_source_files");
        jdbc.update("DELETE FROM calculation_results");
        jdbc.update("DELETE FROM calculation_runs");
        jdbc.update("DELETE FROM product_daily_stocks");
        jdbc.update("DELETE FROM daily_stock_records");
        jdbc.update("DELETE FROM replenishment_settings");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void isEmptyBeforeAnyCalculation() throws Exception {
        long userId = newUser();
        long product = product(userId, "8800000000001", "케어트릿 120g");

        get(userId, NEEDS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("asOfDate").doesNotExist())
                .andExpect(jsonPath("today").value(TODAY.toString()))
                .andExpect(jsonPath("resultStatus").doesNotExist())
                .andExpect(jsonPath("summary.needCount").value(0))
                .andExpect(jsonPath("items.length()").value(0));
        get(userId, detail(product))
                .andExpect(status().isOk())
                .andExpect(jsonPath("product.skuName").value("케어트릿 120g"))
                .andExpect(jsonPath("product.fulfillment").value("기본 창고"))
                .andExpect(jsonPath("needStatus").doesNotExist())
                .andExpect(jsonPath("settings.leadTimeDays").value(5))
                .andExpect(jsonPath("settings.leadTimeSource").value("SUGGESTED"))
                .andExpect(jsonPath("settings.reviewCycleSource").value("ESTIMATED"))
                .andExpect(jsonPath("result").doesNotExist())
                .andExpect(jsonPath("trend.length()").value(0));
    }

    @Test
    void classifiesLatestDoneRunByTodayAndLeadTime() throws Exception {
        long userId = newUser();
        long warehouse = warehouseOf(userId);
        long soon = product(userId, "8800000000001", "케어트릿 120g");
        long sooner = product(userId, "8800000000002", "덴탈껌 S");
        long later = product(userId, "8800000000003", "케어 샴푸");
        long noData = product(userId, "8800000000004", "시즌 간식 세트");
        long mismatch = product(userId, "8800000000005", "덴탈껌 M");

        long old = run(warehouse, TODAY.minusDays(10), "DONE");
        result(old, soon, "OK", TODAY, 999L, "999000", "0.9000", null);
        long latest = run(warehouse, TODAY.minusDays(2), "DONE");
        result(latest, soon, "OK", TODAY.plusDays(2), 72L, "129600.00", "0.0400", null);
        result(latest, sooner, "OK", TODAY.plusDays(1), 12L, "6000.00", "0.3000", null);
        result(latest, later, "OK", TODAY.plusDays(30), 0L, "0", "0.0100", null);
        result(latest, noData, "NOT_CALCULABLE", null, null, null, null, "판매한 날(유효 관측일)이 7일보다 적어요");
        result(latest, mismatch, "EXCLUDED", null, null, null, null, "재고 숫자가 맞지 않아요");

        get(userId, NEEDS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("asOfDate").value(TODAY.minusDays(2).toString()))
                .andExpect(jsonPath("daysSinceAsOf").value(2))
                .andExpect(jsonPath("resultStatus").value("UP_TO_DATE"))
                .andExpect(jsonPath("runId").value(latest))
                .andExpect(jsonPath("resultComputedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("lastFailedRunAt").doesNotExist())
                .andExpect(jsonPath("summary.needCount").value(2))
                .andExpect(jsonPath("summary.notNeededCount").value(1))
                .andExpect(jsonPath("summary.notCalculableCount").value(1))
                .andExpect(jsonPath("summary.excludedCount").value(1))
                .andExpect(jsonPath("items[0].productId").value(sooner))
                .andExpect(jsonPath("items[1].productId").value(soon))
                .andExpect(jsonPath("items[1].recommendedQty").value(72))
                .andExpect(jsonPath("items[1].recommendedAmount").value(129600))
                .andExpect(jsonPath("items[1].leadTimeDays").value(5))
                .andExpect(jsonPath("items[1].preArrivalRisk").value(0.04))
                .andExpect(jsonPath("notCalculable[0].reason").value("판매한 날(유효 관측일)이 7일보다 적어요"))
                .andExpect(jsonPath("excluded[0].skuName").value("덴탈껌 M"))
                .andExpect(jsonPath("excluded[0].date").doesNotExist());
        get(userId, NEEDS + "?sort=risk").andExpect(jsonPath("items[0].productId").value(sooner));
        get(userId, NEEDS + "?sort=amount").andExpect(jsonPath("items[0].productId").value(soon));
        get(userId, NEEDS + "?sort=price").andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_INPUT"));

        // 상품별 리드타임을 줄이면 같은 소진일이라도 "아직 괜찮아요"가 된다.
        mvc.perform(put("/api/products/" + soon + "/replenishment-settings").with(as(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadTimeDays\": 1}"))
                .andExpect(status().isOk());
        get(userId, NEEDS).andExpect(jsonPath("summary.needCount").value(1));
        get(userId, detail(soon))
                .andExpect(jsonPath("needStatus").value("NOT_NEEDED"))
                .andExpect(jsonPath("settings.leadTimeSource").value("PRODUCT"));
    }

    @Test
    void showsPreviousResultAsStaleWhenLatestRunFailed() throws Exception {
        long userId = newUser();
        long warehouse = warehouseOf(userId);
        long product = product(userId, "8800000000001", "케어트릿 120g");
        long done = run(warehouse, TODAY.minusDays(1), "DONE");
        result(done, product, "OK", TODAY.plusDays(1), 10L, "1000", "0.1000", null);
        run(warehouse, TODAY, "FAILED");

        get(userId, NEEDS)
                .andExpect(jsonPath("resultStatus").value("STALE"))
                .andExpect(jsonPath("runId").value(done))
                .andExpect(jsonPath("lastFailedRunAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("summary.needCount").value(1));
    }

    @Test
    void asksForReuploadWhenAsOfDateIsTooOld() throws Exception {
        long userId = newUser();
        long product = product(userId, "8800000000001", "케어트릿 120g");
        long run = run(warehouseOf(userId), TODAY.minusDays(8), "DONE");
        result(run, product, "OK", TODAY.plusDays(1), 10L, "1000", "0.1000", null);

        get(userId, NEEDS)
                .andExpect(jsonPath("daysSinceAsOf").value(8))
                .andExpect(jsonPath("summary.needCount").value(0))
                .andExpect(jsonPath("notCalculable[0].productId").value(product))
                .andExpect(jsonPath("notCalculable[0].reason").isString());
        get(userId, detail(product)).andExpect(jsonPath("needStatus").value("REUPLOAD_REQUIRED"));
    }

    @Test
    void detailShowsResultAccuracyAndDisplayNumbers() throws Exception {
        long userId = newUser();
        long product = product(userId, "8800000000001", "케어트릿 120g");
        long run = run(warehouseOf(userId), TODAY.minusDays(2), "DONE");
        jdbc.update("""
                INSERT INTO calculation_results (run_id, product_id, origin_date, start_date, method, method_reason,
                    multiplier, horizon_days, start_stock_qty, planned_receipt_qty, forecast_outbound_qty,
                    pre_arrival_unmet_qty, recommended_qty, recommended_amount, expected_shortage_on, status,
                    input_snapshot, pre_arrival_risk, accuracy_grade, valid_observation_days, cover_success_count,
                    cover_trial_count, forecast_error_rate, forecast_range_low, forecast_range_high)
                VALUES (?, ?, ?, ?, 'A', '평가 결과 기본 방식', 1.050, 34, 40.700, 0, 101.200, 0.200, 72, 129600.00, ?,
                    'OK', JSON '{}', 0.0400, 'LOW', 20, 3, 5, 0.3100, 10.200, 20.700)
                """, run, product, TODAY.minusDays(2), TODAY, TODAY.plusDays(3));

        get(userId, detail(product) + "?from=2026-07-01&to=2026-10-14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("needStatus").value("NEED_NOW"))
                .andExpect(jsonPath("asOfDate").value(TODAY.minusDays(2).toString()))
                .andExpect(jsonPath("expectedShortageOn").value(TODAY.plusDays(3).toString()))
                .andExpect(jsonPath("result.method").value("A"))
                .andExpect(jsonPath("result.multiplier").value(1.05))
                .andExpect(jsonPath("result.horizonDays").value(34))
                .andExpect(jsonPath("result.startStockQty").value(40))
                .andExpect(jsonPath("result.startStockEstimated").value(true))
                .andExpect(jsonPath("result.forecastOutboundQty").value(102))
                .andExpect(jsonPath("result.preArrivalUnmetQty").value(1))
                .andExpect(jsonPath("result.plannedReceiptsInHorizon").value(0))
                .andExpect(jsonPath("result.requiredQtyBeforeRounding").doesNotExist())
                .andExpect(jsonPath("result.recommendedQty").value(72))
                .andExpect(jsonPath("result.recommendedAmount").value(129600))
                .andExpect(jsonPath("accuracy.grade").value("LOW"))
                .andExpect(jsonPath("accuracy.isReference").value(true))
                .andExpect(jsonPath("accuracy.coverSuccessCount").value(3))
                .andExpect(jsonPath("accuracy.range.low").value(11))
                .andExpect(jsonPath("accuracy.range.high").value(21))
                .andExpect(jsonPath("accuracy.history.length()").value(0))
                .andExpect(jsonPath("accuracy.firstInboundOn").value("2026-09-01"));
    }

    @Test
    void detailChecksOwnerAndPeriod() throws Exception {
        long owner = newUser();
        long other = newUser();
        long product = product(owner, "8800000000001", "케어트릿 120g");

        get(other, detail(product)).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        get(owner, detail(Long.MAX_VALUE)).andExpect(status().isNotFound());
        get(owner, detail(product) + "?from=2026-10-14&to=2026-07-01").andExpect(status().isBadRequest());
        get(owner, detail(product) + "?from=2026-13-01").andExpect(status().isBadRequest());
        // 다른 회원의 계산 결과는 대시보드에 보이지 않는다.
        long run = run(warehouseOf(owner), TODAY, "DONE");
        result(run, product, "OK", TODAY, 1L, "100", "0.5000", null);
        get(other, NEEDS).andExpect(jsonPath("items.length()").value(0)).andExpect(jsonPath("runId").doesNotExist());
    }

    private long run(long warehouseId, LocalDate asOfDate, String status) {
        jdbc.update("""
                INSERT INTO calculation_runs (warehouse_id, run_type, as_of_date, status, engine_version,
                    selection_snapshot, created_at, completed_at)
                VALUES (?, 'CALCULATE', ?, ?, '0.0.0', JSON '{}', ?, ?)
                """, warehouseId, asOfDate, status, Timestamp.from(Instant.now()),
                "DONE".equals(status) ? Timestamp.from(Instant.now()) : null);
        return jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs", Long.class);
    }

    private void result(long runId, long productId, String status, LocalDate shortageOn, Long qty, String amount,
            String risk, String reason) {
        jdbc.update("""
                INSERT INTO calculation_results (run_id, product_id, origin_date, start_date, multiplier, horizon_days,
                    recommended_qty, recommended_amount, expected_shortage_on, status, reason, input_snapshot,
                    pre_arrival_risk)
                VALUES (?, ?, ?, ?, 1.000, 30, ?, CAST(? AS NUMERIC(18, 2)), ?, ?, ?, JSON '{}',
                    CAST(? AS NUMERIC(5, 4)))
                """, runId, productId, TODAY.minusDays(2), TODAY, qty, amount, shortageOn, status, reason, risk);
    }

    private ResultActions get(long userId, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(as(userId)));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(long userId) {
        return jwt().jwt(claims -> claims.subject(Long.toString(userId)));
    }

    private static String detail(long productId) {
        return "/api/products/" + productId + "/detail";
    }

    private long warehouseOf(long userId) {
        return warehouses.lockDefault(userId).getId();
    }

    private long product(long userId, String barcode, String name) {
        registration.register(userId, warehouseOf(userId),
                List.of(new CleanedStockUnit(barcode, name, "MIX-" + barcode, LocalDate.of(2026, 9, 1))));
        return products.findByUserIdAndSkuBarcodeIn(userId, List.of(barcode)).getFirst().getId();
    }

    private long newUser() {
        String email = "dashboard-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
