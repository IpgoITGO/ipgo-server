package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleanedStockUnit;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.service.ProductRegistrationService;
import com.ipgo.server.service.WarehouseService;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// K2 시나리오 비교표. 계산 연동 전이라 계산 결과와 시나리오는 표에 직접 넣는다.
@AutoConfigureMockMvc
class ScenarioIntegrationTests extends DatabaseIntegrationTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 14);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;

    private int barcodeSeq;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM scenario_results");
        jdbc.update("DELETE FROM calculation_results");
        jdbc.update("DELETE FROM calculation_runs");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void showsScenariosInFixedOrderWithDisplayNumbers() throws Exception {
        long userId = newUser();
        long result = result(userId, "OK", AS_OF.plusDays(2), "0.0400", 38, "SKU", "HIGH");
        scenario(result, "CUSTOM", 84, false, null, "18.000", "0.000", "0.0300");
        scenario(result, "P120", 96, false, null, "30.900", "0.000", "0.0200");
        scenario(result, "P80", 60, false, AS_OF.plusDays(35), "0.000", "5.100", "0.2500");
        scenario(result, "P100", 72, true, null, "6.700", "0.000", "0.0800");

        scenarios(userId, result)
                .andExpect(status().isOk())
                .andExpect(jsonPath("resultId").value(result))
                .andExpect(jsonPath("isEstimate").value(true))
                .andExpect(jsonPath("preArrivalRisk").value(0.04))
                .andExpect(jsonPath("riskSampleSize").value(38))
                .andExpect(jsonPath("riskBasis").value("SKU"))
                .andExpect(jsonPath("riskStatus").value("OK"))
                .andExpect(jsonPath("scenarios.length()").value(4))
                .andExpect(jsonPath("scenarios[0].type").value("P80"))
                .andExpect(jsonPath("scenarios[0].qty").value(60))
                .andExpect(jsonPath("scenarios[0].expectedDepletionOn").value(AS_OF.plusDays(35).toString()))
                .andExpect(jsonPath("scenarios[0].unmetDemandQty").value(6))
                .andExpect(jsonPath("scenarios[0].postArrivalRisk").value(0.25))
                .andExpect(jsonPath("scenarios[1].type").value("P100"))
                .andExpect(jsonPath("scenarios[1].isDuplicate").value(true))
                .andExpect(jsonPath("scenarios[1].expectedDepletionOn").doesNotExist())
                .andExpect(jsonPath("scenarios[1].expectedEndingStock").value(6))
                .andExpect(jsonPath("scenarios[2].type").value("P120"))
                .andExpect(jsonPath("scenarios[2].expectedEndingStock").value(30))
                .andExpect(jsonPath("scenarios[3].type").value("CUSTOM"));
    }

    @Test
    void splitsUnmetDemandIntoBeforeAndAfterArrival() throws Exception {
        long userId = newUser();
        long result = result(userId, "OK", AS_OF, "0.0400", 38, "SKU", "HIGH");
        // 도착 전에는 넣는 양과 관계없이 4.6개를 못 판다. 시나리오의 unmet은 도착 전 + 도착 후 합계다.
        jdbc.update("UPDATE calculation_results SET pre_arrival_unmet_qty = 4.600 WHERE id = ?", result);
        scenario(result, "P80", 32, false, AS_OF.plusDays(9), "0.000", "12.600", "0.2500");
        scenario(result, "P100", 40, false, null, "0.000", "4.900", "0.0800");
        scenario(result, "P120", 48, false, null, "8.000", "4.600", "0.0200");

        scenarios(userId, result)
                .andExpect(jsonPath("preArrivalUnmetQty").value(5))
                .andExpect(jsonPath("scenarios[0].unmetDemandQty").value(13))
                .andExpect(jsonPath("scenarios[0].postArrivalUnmetQty").value(8))
                // 올린 정수끼리 빼면 5 − 5 = 0이 되지만, 실제 도착 후 몫 0.3개는 1개로 보여야 한다.
                .andExpect(jsonPath("scenarios[1].unmetDemandQty").value(5))
                .andExpect(jsonPath("scenarios[1].postArrivalUnmetQty").value(1))
                .andExpect(jsonPath("scenarios[2].postArrivalUnmetQty").value(0));
    }

    @Test
    void marksRiskStatusFromStoredValues() throws Exception {
        long userId = newUser();
        long lowGrade = result(userId, "OK", AS_OF, null, 12, null, "LOW");
        long fewSamples = result(userId, "OK", AS_OF, null, 3, null, "MEDIUM");
        long pooled = result(userId, "OK", AS_OF, "0.1000", 40, "POOLED", "MEDIUM");
        long notNeeded = result(userId, "NOT_NEEDED", AS_OF, null, null, null, null);

        scenarios(userId, lowGrade).andExpect(jsonPath("riskStatus").value("LOW_GRADE"))
                .andExpect(jsonPath("preArrivalRisk").doesNotExist())
                .andExpect(jsonPath("isEstimate").value(false));
        scenarios(userId, fewSamples).andExpect(jsonPath("riskStatus").value("INSUFFICIENT_SAMPLE"))
                .andExpect(jsonPath("riskSampleSize").value(3));
        scenarios(userId, pooled).andExpect(jsonPath("riskStatus").value("OK"))
                .andExpect(jsonPath("riskBasis").value("POOLED"));
        // 권장량이 0개면 비교할 시나리오가 없다.
        scenarios(userId, notNeeded).andExpect(status().isOk())
                .andExpect(jsonPath("riskStatus").doesNotExist())
                .andExpect(jsonPath("scenarios.length()").value(0));
    }

    @Test
    void checksOwner() throws Exception {
        long owner = newUser();
        long other = newUser();
        long result = result(owner, "OK", AS_OF, "0.0400", 38, "SKU", "HIGH");

        scenarios(other, result).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        scenarios(owner, Long.MAX_VALUE).andExpect(status().isNotFound());
    }

    // 회원마다 상품 1개를 새로 만들어 결과를 하나 넣는다. startDate가 기준일보다 늦으면 시작 재고 추정이다.
    private long result(long userId, String status, LocalDate startDate, String risk, Integer sampleSize,
            String basis, String grade) {
        long warehouse = warehouses.lockDefault(userId).getId();
        String barcode = "88000000%05d".formatted(++barcodeSeq);
        registration.register(userId, warehouse,
                List.of(new CleanedStockUnit(barcode, "케어트릿 120g", "MIX-" + barcode, LocalDate.of(2026, 9, 1))));
        long product = products.findByUserIdAndSkuBarcodeIn(userId, List.of(barcode)).getFirst().getId();
        jdbc.update("""
                INSERT INTO calculation_runs (warehouse_id, run_type, as_of_date, status, engine_version,
                    selection_snapshot, created_at, completed_at)
                VALUES (?, 'CALCULATE', ?, 'DONE', '0.0.0', JSON '{}', ?, ?)
                """, warehouse, AS_OF, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        long run = jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs", Long.class);
        jdbc.update("""
                INSERT INTO calculation_results (run_id, product_id, origin_date, start_date, multiplier, horizon_days,
                    status, input_snapshot, pre_arrival_risk, risk_sample_size, risk_basis, accuracy_grade)
                VALUES (?, ?, ?, ?, 1.000, 30, ?, JSON '{}', CAST(? AS NUMERIC(5, 4)), ?, ?, ?)
                """, run, product, AS_OF, startDate, status, risk, sampleSize, basis, grade);
        return jdbc.queryForObject("SELECT MAX(id) FROM calculation_results", Long.class);
    }

    private void scenario(long resultId, String type, long qty, boolean duplicate, LocalDate depletionOn,
            String endingStock, String unmet, String risk) {
        jdbc.update("""
                INSERT INTO scenario_results (result_id, scenario_type, scenario_qty, is_duplicate,
                    expected_depletion_on, expected_ending_stock, unmet_demand_qty, post_arrival_risk, daily_projection)
                VALUES (?, ?, ?, ?, ?, CAST(? AS NUMERIC(18, 3)), CAST(? AS NUMERIC(18, 3)),
                    CAST(? AS NUMERIC(5, 4)), JSON '[]')
                """, resultId, type, qty, duplicate, depletionOn, endingStock, unmet, risk);
    }

    private ResultActions scenarios(long userId, long resultId) throws Exception {
        return mvc.perform(get("/api/results/" + resultId + "/scenarios")
                .with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))));
    }

    private long newUser() {
        String email = "scenario-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
