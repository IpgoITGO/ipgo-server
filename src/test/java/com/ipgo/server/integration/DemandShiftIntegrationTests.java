package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.support.UploadTestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #193 판매 급변(demandShift). 기준일까지 최근 7일 하루 평균과 그 전 28일 평균을 비교한다.
@AutoConfigureMockMvc
class DemandShiftIntegrationTests extends DatabaseIntegrationTest {
    static final LocalDate AS_OF = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadFileRepository uploads;
    long owner, warehouse, upload;

    @BeforeEach void fixture() {
        UploadTestDatabase.clear(jdbc);
        owner = UploadTestDatabase.newUser(jdbc, "shift-" + UUID.randomUUID() + "@example.test");
        jdbc.update("INSERT INTO warehouses (user_id, name) VALUES (?, '기본 창고')", owner);
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id = ?", Long.class, owner);
        upload = done(YearMonth.from(AS_OF), 1);
    }

    @AfterEach void cleanup() { UploadTestDatabase.clear(jdbc); }

    @Test void flagsOnlyBigJumpsAndKeepsSmallOrSteadyOrNewProductsNull() throws Exception {
        long spike = product("SPIKE", day -> recent(day) ? 21 : 2);          // 2개 → 21개: 10.5배, 19개 늘어 UP
        long tiny = product("TINY", day -> recent(day) ? 2 : 0);             // 0 → 2개: 늘어난 양이 10개 미만
        long steady = product("STEADY", day -> 20);                          // 그대로
        long almost = product("ALMOST", day -> recent(day) ? 14 : 5);        // 2.8배: 3배 미만
        long newbie = product("NEW", day -> recent(day) ? 30 : null);        // 이전 기록 없음
        long run = run(AS_OF);
        for (long product : List.of(spike, tiny, steady, almost, newbie)) result(run, product, 50);

        dashboard().andExpect(jsonPath(shift(spike) + ".direction", contains("UP")))
                .andExpect(jsonPath(shift(spike) + ".recentDailyQty", contains(21.0)))
                .andExpect(jsonPath(shift(spike) + ".baselineDailyQty", contains(2.0)))
                .andExpect(jsonPath(shift(spike) + ".ratio", contains(10.5)))
                .andExpect(jsonPath(shift(spike) + ".since", contains(AS_OF.minusDays(6).toString())))
                .andExpect(jsonPath(shift(spike) + ".previousRecommendedQty", contains((Object) null)));
        for (long product : List.of(tiny, steady, almost, newbie)) {
            dashboard().andExpect(jsonPath("$..[?(@.productId == " + product + ")].productId", contains((int) product)))
                    .andExpect(jsonPath(shift(product) + ".direction", empty()));
        }

        mvc.perform(get("/api/products/" + spike + "/detail").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("demandShift.direction").value("UP"))
                .andExpect(jsonPath("demandShift.ratio").value(10.5));
        mvc.perform(get("/api/products/" + steady + "/detail").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("demandShift").doesNotExist());
    }

    @Test void downWhenTheLastSpikeEndedAndRecommendationHalved() throws Exception {
        // 지난 계산(기준일 7일 전)의 최근 7일에 30개씩 팔렸다가, 이번 7일은 3개로 줄었다.
        LocalDate previousAsOf = AS_OF.minusDays(7);
        Function<LocalDate, Integer> burst = day -> !day.isBefore(previousAsOf.minusDays(6)) && !day.isAfter(previousAsOf) ? 30
                : recent(day) ? 3 : 2;
        long ended = product("ENDED", burst);
        long eased = product("EASED", burst);       // 급증은 끝났지만 권장량이 절반보다 덜 줄었다
        long previous = run(previousAsOf);
        result(previous, ended, 290);
        result(previous, eased, 290);
        long run = run(AS_OF);
        result(run, ended, 100);
        result(run, eased, 200);

        dashboard().andExpect(jsonPath(shift(ended) + ".direction", contains("DOWN")))
                .andExpect(jsonPath(shift(ended) + ".previousRecommendedQty", contains(290)))
                .andExpect(jsonPath(shift(ended) + ".recentDailyQty", contains(3.0)))
                .andExpect(jsonPath(shift(eased) + ".direction", empty()));
        mvc.perform(get("/api/products/" + ended + "/detail").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("demandShift.direction").value("DOWN"));
    }

    // 휘님 리뷰: 지난 계산은 충분한 기록으로 UP이었는데, 같은 달을 덮어쓴 이번 수불부에는 최근 7일만 있다.
    // 이번에는 비교할 기록이 부족해 판단하지 않으므로, 권장량이 크게 줄어도 DOWN이 아니라 null이다.
    @Test void noDownWhenThisRunHasTooLittleHistoryToJudge() throws Exception {
        LocalDate previousAsOf = AS_OF.minusDays(7);
        long gap = product("GAP", day -> !day.isBefore(previousAsOf.minusDays(6)) && !day.isAfter(previousAsOf) ? 30 : 2);
        long previous = run(previousAsOf, upload);
        result(previous, gap, 290);
        long rewritten = done(YearMonth.from(AS_OF), 2);
        stocks(gap, rewritten, day -> recent(day) ? 3 : null);
        long run = run(AS_OF, rewritten);
        result(run, gap, 100);

        dashboard().andExpect(jsonPath("$..[?(@.productId == " + gap + ")].productId", contains((int) gap)))
                .andExpect(jsonPath(shift(gap) + ".direction", empty()));
        mvc.perform(get("/api/products/" + gap + "/detail").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("demandShift").doesNotExist());
    }

    private static boolean recent(LocalDate day) {
        return !day.isBefore(AS_OF.minusDays(6));
    }

    // 대시보드의 어느 목록(지금 넣기 · 괜찮아요 · 계산 못 해요)에 있든 그 상품의 demandShift
    private static String shift(long productId) {
        return "$..[?(@.productId == " + productId + ")].demandShift";
    }

    private ResultActions dashboard() throws Exception {
        return mvc.perform(get("/api/dashboard/replenishment-needs").with(as(owner))).andExpect(status().isOk());
    }

    // 기준일 42일 전부터 기준일까지 하루 출고. null이면 그날 기록이 없다.
    private long product(String barcode, Function<LocalDate, Integer> outbound) {
        jdbc.update("INSERT INTO products (user_id, sku_barcode, sku_name) VALUES (?, ?, ?)", owner, barcode, barcode);
        long id = jdbc.queryForObject("SELECT id FROM products WHERE user_id = ? AND sku_barcode = ?", Long.class, owner, barcode);
        stocks(id, upload, outbound);
        return id;
    }

    private void stocks(long productId, long uploadId, Function<LocalDate, Integer> outbound) {
        for (LocalDate day = AS_OF.minusDays(42); !day.isAfter(AS_OF); day = day.plusDays(1)) {
            Integer qty = outbound.apply(day);
            if (qty == null) continue;
            jdbc.update("""
                    INSERT INTO product_daily_stocks (product_id, upload_file_id, stock_date, opening_qty, inbound_qty,
                        outbound_qty, adjustment_qty, closing_qty, is_valid_observation) VALUES (?, ?, ?, 500, 0, ?, 0, ?, TRUE)
                    """, productId, uploadId, day, qty, 500 - qty);
        }
    }

    private long done(YearMonth month, int revision) {
        UploadFile file = uploads.saveAndFlush(new UploadFile(warehouse, month + ".xlsx", UUID.randomUUID().toString(),
                "a".repeat(64), revision, new UploadPeriod(month)));
        LocalDate end = file.getPeriodEnd();
        file.complete(end, new UploadProcessingReport(end, new UploadProcessingReport.Validation(1, 1, List.of()), null, null));
        return uploads.saveAndFlush(file).getId();
    }

    private long run(LocalDate asOf) {
        return run(asOf, upload);
    }

    private long run(LocalDate asOf, long source) {
        jdbc.update("""
                INSERT INTO calculation_runs (warehouse_id, run_type, as_of_date, status, engine_version,
                    selection_snapshot, created_at, completed_at)
                VALUES (?, 'CALCULATE', ?, 'DONE', '0.0.0', JSON '{}', ?, ?)
                """, warehouse, asOf, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs", Long.class);
        jdbc.update("INSERT INTO run_source_files (run_id, upload_file_id) VALUES (?, ?)", id, source);
        return id;
    }

    private void result(long runId, long productId, long recommendedQty) {
        LocalDate asOf = jdbc.queryForObject("SELECT as_of_date FROM calculation_runs WHERE id = ?", LocalDate.class, runId);
        jdbc.update("""
                INSERT INTO calculation_results (run_id, product_id, origin_date, start_date, multiplier, horizon_days,
                    recommended_qty, recommended_amount, expected_shortage_on, status, input_snapshot)
                VALUES (?, ?, ?, ?, 1.000, 30, ?, 1000, ?, 'OK', JSON '{}')
                """, runId, productId, asOf, asOf.plusDays(1), recommendedQty, asOf.plusDays(10));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(long userId) {
        return jwt().jwt(claims -> claims.subject(Long.toString(userId)));
    }
}
