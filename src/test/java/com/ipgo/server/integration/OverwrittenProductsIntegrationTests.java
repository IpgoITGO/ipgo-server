package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.service.CurrentProducts;
import com.ipgo.server.support.UploadTestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #188 같은 달을 덮어쓰면 옛 파일에만 있던 상품은 목록 · 대시보드 · 계산에서 빠진다.
// 9월: 쿠키 · 껌, 10월 테스트: 사탕 → 실제 10월(쿠키)로 덮어쓰면 쿠키 · 껌만 남는다.
@AutoConfigureMockMvc
class OverwrittenProductsIntegrationTests extends DatabaseIntegrationTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadFileRepository uploads;
    @Autowired CurrentProducts current;
    long owner, warehouse, cookie, gum, candy;

    @BeforeEach void fixture() {
        UploadTestDatabase.clear(jdbc);
        owner = UploadTestDatabase.newUser(jdbc, "overwrite-" + UUID.randomUUID() + "@example.test");
        jdbc.update("INSERT INTO warehouses (user_id, name) VALUES (?, '기본 창고')", owner);
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id = ?", Long.class, owner);
        cookie = product("COOKIE", "쿠키");
        gum = product("GUM", "껌");
        candy = product("CANDY", "사탕");
        stock(done("2026-09", 1), cookie, gum);
        stock(done("2026-10", 1), candy);      // 테스트로 올린 10월
    }

    @AfterEach void cleanup() { UploadTestDatabase.clear(jdbc); }

    @Test void overwritingAMonthHidesProductsOnlyInTheOldFile() throws Exception {
        assertThat(current.overwritten(warehouse)).isEmpty();
        products().andExpect(jsonPath("items[*].skuName", containsInAnyOrder("쿠키", "껌", "사탕")));

        stock(done("2026-10", 2), cookie);    // 실제 10월로 덮어씀

        assertThat(current.overwritten(warehouse)).containsExactly(candy);
        // 껌은 10월엔 없지만 9월 파일에 있어서 남아요.
        products().andExpect(jsonPath("total").value(2))
                .andExpect(jsonPath("items[*].skuName", containsInAnyOrder("쿠키", "껌")));
    }

    @Test void dashboardAndRunSummaryDropOverwrittenProductsFromOlderResults() throws Exception {
        long run = run();
        for (long product : List.of(cookie, gum, candy)) result(run, product);
        stock(done("2026-10", 2), cookie);

        mvc.perform(get("/api/dashboard/replenishment-needs").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("items.length()").value(2))
                .andExpect(jsonPath("items[*].productId", containsInAnyOrder((int) cookie, (int) gum)));
        mvc.perform(get("/api/calculation-runs/" + run).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("summary.needCount").value(2));
    }

    @Test void hiddenProductComesBackWhenANewFileHasItAgainAndKeepsItsSettings() throws Exception {
        jdbc.update("UPDATE products SET unit_cost = 777 WHERE id = ?", candy);
        stock(done("2026-10", 2), cookie);
        assertThat(current.overwritten(warehouse)).containsExactly(candy);

        stock(done("2026-11", 1), cookie, candy);  // 다음 달 파일에 사탕이 다시 나옴

        assertThat(current.overwritten(warehouse)).isEmpty();
        products().andExpect(jsonPath("total").value(3));
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM products WHERE id = ?", Long.class, candy)).isEqualTo(777);
    }

    @Test void nothingIsHiddenWhileTheOnlyFileOfEveryMonthIsStillProcessing() {
        UploadTestDatabase.clear(jdbc);
        jdbc.update("INSERT INTO warehouses (user_id, name) VALUES (?, '기본 창고')", owner);
        long fresh = jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id = ?", Long.class, owner);
        uploads.saveAndFlush(new UploadFile(fresh, "2026-10.xlsx", UUID.randomUUID().toString(), "a".repeat(64), 1,
                new UploadPeriod(YearMonth.parse("2026-10"))));
        assertThat(current.overwritten(fresh)).isEmpty();
    }

    private ResultActions products() throws Exception {
        return mvc.perform(get("/api/products").with(as(owner))).andExpect(status().isOk());
    }

    private long product(String barcode, String name) {
        jdbc.update("INSERT INTO products (user_id, sku_barcode, sku_name) VALUES (?, ?, ?)", owner, barcode, name);
        return jdbc.queryForObject("SELECT id FROM products WHERE user_id = ? AND sku_barcode = ?", Long.class, owner, barcode);
    }

    private UploadFile done(String month, int revision) {
        UploadFile upload = uploads.saveAndFlush(new UploadFile(warehouse, month + ".xlsx", UUID.randomUUID().toString(),
                "a".repeat(64), revision, new UploadPeriod(YearMonth.parse(month))));
        LocalDate end = upload.getPeriodEnd();
        upload.complete(end, new UploadProcessingReport(end, new UploadProcessingReport.Validation(1, 1, List.of()), null, null));
        return uploads.saveAndFlush(upload);
    }

    private void stock(UploadFile upload, long... products) {
        for (long product : products) jdbc.update("""
                INSERT INTO product_daily_stocks (product_id, upload_file_id, stock_date, opening_qty, inbound_qty,
                    outbound_qty, adjustment_qty, closing_qty, is_valid_observation) VALUES (?, ?, ?, 5, 0, 0, 0, 5, TRUE)
                """, product, upload.getId(), upload.getPeriodEnd());
    }

    private long run() {
        jdbc.update("""
                INSERT INTO calculation_runs (warehouse_id, run_type, as_of_date, status, engine_version,
                    selection_snapshot, created_at, completed_at)
                VALUES (?, 'CALCULATE', ?, 'DONE', '0.0.0', JSON '{}', ?, ?)
                """, warehouse, TODAY.minusDays(1), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs", Long.class);
    }

    private void result(long runId, long productId) {
        jdbc.update("""
                INSERT INTO calculation_results (run_id, product_id, origin_date, start_date, multiplier, horizon_days,
                    recommended_qty, recommended_amount, expected_shortage_on, status, input_snapshot)
                VALUES (?, ?, ?, ?, 1.000, 30, 10, 1000, ?, 'OK', JSON '{}')
                """, runId, productId, TODAY.minusDays(1), TODAY, TODAY.plusDays(3));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(long userId) {
        return jwt().jwt(claims -> claims.subject(Long.toString(userId)));
    }
}
