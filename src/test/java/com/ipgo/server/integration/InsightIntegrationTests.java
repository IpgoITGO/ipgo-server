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

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// K4 AI 인사이트 1차. 계산 연동 전이라 계산 결과와 저장된 인사이트는 표에 직접 넣는다.
@AutoConfigureMockMvc
class InsightIntegrationTests extends DatabaseIntegrationTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 14);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRegistrationService registration;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRepository products;

    private int barcodeSeq;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM ai_explanations");
        jdbc.update("DELETE FROM calculation_results");
        jdbc.update("DELETE FROM calculation_runs");
        jdbc.update("DELETE FROM inventory_units");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM warehouses");
    }

    @Test
    void returnsFixedSentenceWithoutLlmForStatusesThatSkipIt() throws Exception {
        long userId = newUser();
        long notCalculable = result(userId, "NOT_CALCULABLE", "판매한 날(유효 관측일)이 7일보다 적어요.");
        long excluded = result(userId, "EXCLUDED", "재고 숫자가 맞지 않아요");
        long reupload = result(userId, "REUPLOAD_REQUIRED", "기준일이 7일 넘게 지났어요");
        long noReason = result(userId, "NOT_CALCULABLE", null);

        insight(userId, notCalculable)
                .andExpect(status().isOk())
                .andExpect(jsonPath("resultId").value(notCalculable))
                .andExpect(jsonPath("mode").value("FIXED"))
                .andExpect(jsonPath("isFallback").value(false))
                .andExpect(jsonPath("text").value("이 상품은 권장 입고량을 계산하지 못했습니다. 사유: 판매한 날(유효 관측일)이 7일보다 적어요."))
                .andExpect(jsonPath("generatedAt").doesNotExist());
        insight(userId, excluded).andExpect(jsonPath("text")
                .value("이 상품은 재고 기록 검산이 맞지 않아 이번 계산에서 제외했습니다. 사유: 재고 숫자가 맞지 않아요."));
        insight(userId, reupload).andExpect(jsonPath("text")
                .value("이 상품은 수불부를 다시 올려야 계산할 수 있습니다. 사유: 기준일이 7일 넘게 지났어요."));
        // 문장에 넣을 값이 없으면 문장 없이 비교표만 보여준다.
        insight(userId, noReason).andExpect(jsonPath("mode").value("TABLE_ONLY"))
                .andExpect(jsonPath("text").doesNotExist());
    }

    @Test
    void fillsNotNeededSentenceWithDisplayNumbers() throws Exception {
        long userId = newUser();
        long notNeeded = result(userId, "NOT_NEEDED", null);
        jdbc.update("""
                UPDATE calculation_results SET method = 'B', horizon_days = 34, forecast_outbound_qty = 1209.200,
                    start_stock_qty = 500.900, planned_receipt_qty = 100 WHERE id = ?
                """, notNeeded);

        insight(userId, notNeeded)
                .andExpect(jsonPath("mode").value("FIXED"))
                .andExpect(jsonPath("text").value("계산식 B로 예측한 커버 기간 34일의 예상 출고량은 1,210개이고, "
                        + "현재 재고 500개와 도착 예정 물량 100개로 충당되어 이번에는 입고가 필요하지 않습니다."));
    }

    @Test
    void readsStoredInsightOrSaysGenerating() throws Exception {
        long userId = newUser();
        long ai = result(userId, "OK", null);
        long fallback = result(userId, "OK", null);
        long notCalled = result(userId, "OK", null);
        long tableOnly = result(userId, "OK", null);
        long generating = result(userId, "OK", null);
        explanation(ai, "LLM", null, "200개를 넣으면 다음 입고 때 40개가 남습니다.");
        explanation(fallback, "TEMPLATE", "LLM_TIMEOUT", "기본 문장입니다.");
        explanation(notCalled, "TEMPLATE", null, "기본 문장입니다.");
        explanation(tableOnly, "NONE", "VALIDATION_FAILED", null);

        insight(userId, ai)
                .andExpect(status().isOk())
                .andExpect(jsonPath("mode").value("AI"))
                .andExpect(jsonPath("isFallback").value(false))
                .andExpect(jsonPath("text").value("200개를 넣으면 다음 입고 때 40개가 남습니다."))
                .andExpect(jsonPath("generatedAt").value(endsWith("+09:00")));
        insight(userId, fallback).andExpect(jsonPath("mode").value("FIXED"))
                .andExpect(jsonPath("isFallback").value(true));
        insight(userId, notCalled).andExpect(jsonPath("mode").value("FIXED"))
                .andExpect(jsonPath("isFallback").value(false));
        insight(userId, tableOnly).andExpect(jsonPath("mode").value("TABLE_ONLY"))
                .andExpect(jsonPath("isFallback").value(true))
                .andExpect(jsonPath("text").doesNotExist());
        insight(userId, generating).andExpect(status().isAccepted())
                .andExpect(jsonPath("status").value("GENERATING"));
    }

    @Test
    void checksOwner() throws Exception {
        long owner = newUser();
        long other = newUser();
        long result = result(owner, "NOT_CALCULABLE", "사유");

        insight(other, result).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        insight(owner, Long.MAX_VALUE).andExpect(status().isNotFound());
    }

    // 회원마다 상품 1개를 새로 만들어 결과를 하나 넣는다.
    private long result(long userId, String status, String reason) {
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
                    status, reason, input_snapshot)
                VALUES (?, ?, ?, ?, 1.000, 30, ?, ?, JSON '{}')
                """, run, product, AS_OF, AS_OF, status, reason);
        return jdbc.queryForObject("SELECT MAX(id) FROM calculation_results", Long.class);
    }

    private void explanation(long resultId, String mode, String reasonCode, String text) {
        jdbc.update("""
                INSERT INTO ai_explanations (result_id, mode, status, request_payload, validation_passed,
                    rendered_text, reason_code, versions)
                VALUES (?, ?, 'DONE', JSON '{}', ?, ?, ?, JSON '{}')
                """, resultId, mode, reasonCode == null, text, reasonCode);
    }

    private ResultActions insight(long userId, long resultId) throws Exception {
        return mvc.perform(get("/api/results/" + resultId + "/insight")
                .with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))));
    }

    private long newUser() {
        String email = "insight-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash) VALUES (?, 'test-only-hash')", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
