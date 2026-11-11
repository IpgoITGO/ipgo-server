package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.service.UploadCleaningService;
import com.ipgo.server.support.UploadTestDatabase;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #168 유통기한 저장과 조회: 남은 재고가 있는 LOT만, 유통기한 빠른 순, 임박 기준은 셀러가 바꾼다.
@AutoConfigureMockMvc
class ExpiryIntegrationTests extends DatabaseIntegrationTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadFileRepository uploads;
    @Autowired UploadCleaningService cleaning;
    long owner, warehouse, productA, productB;

    @BeforeEach void fixture() {
        UploadTestDatabase.receiptMatchingSchema(jdbc);
        UploadTestDatabase.expirySchema(jdbc);
        UploadTestDatabase.clear(jdbc);
        owner = UploadTestDatabase.newUser(jdbc, "expiry-" + UUID.randomUUID() + "@example.test");
        warehouse = warehouse(owner);
        productA = product(owner, "A");
        productB = product(owner, "B");
    }

    @AfterEach void cleanup() { UploadTestDatabase.clear(jdbc); }

    @Test void emptyBeforeUploadWithDefaultThirtyDays() throws Exception {
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("asOfDate").value(nullValue()))
                .andExpect(jsonPath("imminentDays").value(30))
                .andExpect(jsonPath("summary.expired").value(0)).andExpect(jsonPath("summary.unknown").value(0))
                .andExpect(jsonPath("items").isEmpty());
        mvc.perform(get("/api/settings/expiry").with(as(owner))).andExpect(jsonPath("imminentDays").value(30));
    }

    @Test void listsLotsWithStockOnAsOfDateByExpiryWithStatusAndSummary() throws Exception {
        var october = done("2026-10", 1);
        LocalDate asOf = october.getPeriodEnd();
        stock(october, lot(productA, "L-EXPIRED", TODAY.minusDays(1)), asOf, 3);
        stock(october, lot(productA, "L-EDGE", TODAY.plusDays(30)), asOf, 5);   // 기준 30일 당일까지 임박
        stock(october, lot(productB, "L-OK", TODAY.plusDays(31)), asOf, 2);
        stock(october, lot(productB, "L-UNKNOWN", null), asOf, 7);
        stock(october, lot(productA, "L-SOLD-OUT", TODAY.plusDays(5)), asOf, 0);           // 다 팔린 LOT은 빼요
        stock(october, lot(productB, "L-OLD", TODAY.plusDays(2)), asOf.minusDays(16), 4);  // 기준일 기록이 없으면 빼요

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("asOfDate").value(asOf.toString()))
                .andExpect(jsonPath("summary.expired").value(1)).andExpect(jsonPath("summary.imminent").value(1))
                .andExpect(jsonPath("summary.ok").value(1)).andExpect(jsonPath("summary.unknown").value(1))
                .andExpect(jsonPath("items.length()").value(4))
                .andExpect(jsonPath("items[0].productId").value(productA))
                .andExpect(jsonPath("items[0].skuBarcode").value("A"))
                .andExpect(jsonPath("items[0].skuName").value("상품A"))
                .andExpect(jsonPath("items[0].receivedOn").value("2026-10-01"))
                .andExpect(jsonPath("items[0].expiryDate").value(TODAY.minusDays(1).toString()))
                .andExpect(jsonPath("items[0].daysLeft").value(-1))
                .andExpect(jsonPath("items[0].remainingQty").value(3))
                .andExpect(jsonPath("items[0].status").value("EXPIRED"))
                .andExpect(jsonPath("items[1].daysLeft").value(30)).andExpect(jsonPath("items[1].status").value("IMMINENT"))
                .andExpect(jsonPath("items[2].status").value("OK"))
                .andExpect(jsonPath("items[3].expiryDate").value(nullValue()))
                .andExpect(jsonPath("items[3].daysLeft").value(nullValue()))
                .andExpect(jsonPath("items[3].status").value("UNKNOWN"));

        // status는 목록만 거르고 summary는 그대로예요.
        list("?status=IMMINENT").andExpect(jsonPath("items.length()").value(1))
                .andExpect(jsonPath("items[0].remainingQty").value(5)).andExpect(jsonPath("summary.expired").value(1));
        // productId가 있으면 summary도 그 상품 안에서만 세요.
        list("?productId=" + productB).andExpect(jsonPath("items.length()").value(2))
                .andExpect(jsonPath("summary.expired").value(0)).andExpect(jsonPath("summary.ok").value(1))
                .andExpect(jsonPath("summary.unknown").value(1));
    }

    @Test void usesLatestRevisionAndSkipsPeriodWhoseLatestRevisionIsNotDone() throws Exception {
        long lot = lot(productA, "L-1", TODAY.plusDays(10));
        var first = done("2026-10", 1);
        stock(first, lot, first.getPeriodEnd(), 9);
        var second = done("2026-10", 2);
        stock(second, lot, second.getPeriodEnd(), 4);
        list("").andExpect(jsonPath("items.length()").value(1)).andExpect(jsonPath("items[0].remainingQty").value(4));

        // 처리 중인 새 revision이 있으면 그 기간의 옛 값은 쓰지 않아요(다른 화면과 같은 규칙).
        uploads.saveAndFlush(new UploadFile(warehouse, "2026-10.xlsx", UUID.randomUUID().toString(), "c".repeat(64), 3,
                new UploadPeriod(YearMonth.parse("2026-10"))));
        list("").andExpect(jsonPath("asOfDate").value(nullValue())).andExpect(jsonPath("items").isEmpty());
    }

    @Test void imminentDaysSettingChangesStatusAndRejectsOutOfRange() throws Exception {
        var october = done("2026-10", 1);
        stock(october, lot(productA, "L-20", TODAY.plusDays(20)), october.getPeriodEnd(), 1);
        list("").andExpect(jsonPath("items[0].status").value("IMMINENT"));

        putSettings("{\"imminentDays\": 10}").andExpect(status().isOk()).andExpect(jsonPath("imminentDays").value(10));
        mvc.perform(get("/api/settings/expiry").with(as(owner))).andExpect(jsonPath("imminentDays").value(10));
        list("").andExpect(jsonPath("imminentDays").value(10)).andExpect(jsonPath("items[0].status").value("OK"))
                .andExpect(jsonPath("summary.imminent").value(0)).andExpect(jsonPath("summary.ok").value(1));

        for (String body : List.of("{\"imminentDays\": 0}", "{\"imminentDays\": 366}", "{}", "{\"imminentDays\": \"열흘\"}")) {
            putSettings(body).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("details.field").value("imminentDays"));
        }
        assertThat(jdbc.queryForObject("SELECT expiry_imminent_days FROM warehouses WHERE id = ?", Integer.class, warehouse))
                .isEqualTo(10);
        // 유통기한 설정은 계산 입력이 아니라서 계산을 시작하지 않아요.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_runs", Integer.class)).isZero();
    }

    @Test void otherMembersLotsAndProductsAreNotVisible() throws Exception {
        long other = UploadTestDatabase.newUser(jdbc, "expiry-other-" + UUID.randomUUID() + "@example.test");
        long otherWarehouse = warehouse(other);
        long otherProduct = product(other, "A");
        var otherUpload = done(otherWarehouse, "2026-10", 1);
        stock(otherUpload, lot(otherWarehouse, otherProduct, "OTHER", TODAY.plusDays(3)), otherUpload.getPeriodEnd(), 8);

        list("").andExpect(jsonPath("items").isEmpty());
        list("?productId=" + otherProduct).andExpect(status().isForbidden());
        list("?productId=999999999").andExpect(status().isNotFound());
        list("?status=SOON").andExpect(status().isBadRequest());
    }

    @Test void cleaningSavesExpiryAndBlankValueDoesNotEraseIt() throws Exception {
        var october = pending("2026-10", 1);
        assertThat(cleaning.complete(october.getId(), october.getJobId(), result(october, LocalDate.of(2027, 3, 15))))
                .isEqualTo(UploadCleaningService.Outcome.APPLIED);
        assertThat(expiryOf("LOT-E")).isEqualTo(LocalDate.of(2027, 3, 15));

        // 다음 달 수불부에 유통기한이 비어 있어도 이미 아는 값은 지우지 않아요.
        var november = pending("2026-11", 1);
        cleaning.complete(november.getId(), november.getJobId(), result(november, null));
        assertThat(expiryOf("LOT-E")).isEqualTo(LocalDate.of(2027, 3, 15));
        list("").andExpect(jsonPath("asOfDate").value("2026-11-30"))
                .andExpect(jsonPath("items[0].expiryDate").value("2027-03-15"));

        // 새 값이 오면 바꿔요.
        var corrected = pending("2026-11", 2);
        cleaning.complete(corrected.getId(), corrected.getJobId(), result(corrected, LocalDate.of(2027, 4, 1)));
        assertThat(expiryOf("LOT-E")).isEqualTo(LocalDate.of(2027, 4, 1));
    }

    private CleaningResult result(UploadFile upload, LocalDate expiry) {
        LocalDate end = upload.getPeriodEnd();
        LocalDate received = LocalDate.of(2026, 10, 1);
        return new CleaningResult(end, end, end,
                List.of(new CleaningResult.CleanProduct("A", "상품A", 1000L, false)),
                List.of(new CleaningResult.Unit("LOT-E", "A", received, expiry)),
                List.of(new CleaningResult.UnitDay(upload.getId(), "LOT-E", received, end, 6L, 0L, 1L, 0L, 5L, "재고변동", 5)),
                List.of(new CleaningResult.ProductDay(upload.getId(), "A", end, 6L, 0L, 1L, 0L, 5L, true, null)),
                new CleaningResult.Validation(1, 1, List.of()), null);
    }

    private LocalDate expiryOf(String lotKey) {
        return jdbc.queryForObject("SELECT expiry_date FROM inventory_units WHERE warehouse_id = ? AND source_inventory_key = ?",
                LocalDate.class, warehouse, lotKey);
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get("/api/expiry" + query).with(as(owner)));
    }

    private ResultActions putSettings(String body) throws Exception {
        return mvc.perform(put("/api/settings/expiry").with(as(owner)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UploadFile pending(String month, int revision) {
        return pending(warehouse, month, revision);
    }

    private UploadFile pending(long w, String month, int revision) {
        return uploads.saveAndFlush(new UploadFile(w, month + ".xlsx", UUID.randomUUID().toString(), "a".repeat(64),
                revision, new UploadPeriod(YearMonth.parse(month))));
    }

    private UploadFile done(String month, int revision) {
        return done(warehouse, month, revision);
    }

    private UploadFile done(long w, String month, int revision) {
        UploadFile upload = pending(w, month, revision);
        LocalDate end = upload.getPeriodEnd();
        upload.complete(end, new UploadProcessingReport(end, new UploadProcessingReport.Validation(1, 1, List.of()), null, null));
        return uploads.saveAndFlush(upload);
    }

    private long lot(long productId, String key, LocalDate expiry) {
        return lot(warehouse, productId, key, expiry);
    }

    private long lot(long w, long productId, String key, LocalDate expiry) {
        jdbc.update("INSERT INTO inventory_units (product_id, warehouse_id, source_inventory_key, received_on, expiry_date) VALUES (?, ?, ?, ?, ?)",
                productId, w, key, LocalDate.of(2026, 10, 1), expiry);
        return jdbc.queryForObject("SELECT id FROM inventory_units WHERE warehouse_id = ? AND source_inventory_key = ?",
                Long.class, w, key);
    }

    private void stock(UploadFile upload, long lotId, LocalDate day, long closing) {
        jdbc.update("""
                INSERT INTO daily_stock_records (upload_file_id, inventory_unit_id, stock_date, opening_qty, inbound_qty,
                    outbound_qty, adjustment_qty, closing_qty, source_sheet, source_row) VALUES (?, ?, ?, ?, 0, 0, 0, ?, 'Sheet1', 5)
                """, upload.getId(), lotId, day, closing, closing);
    }

    private long warehouse(long userId) {
        jdbc.update("INSERT INTO warehouses (user_id, name) VALUES (?, '기본 창고')", userId);
        return jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id = ?", Long.class, userId);
    }

    private long product(long userId, String barcode) {
        jdbc.update("INSERT INTO products (user_id, sku_barcode, sku_name) VALUES (?, ?, ?)", userId, barcode, "상품" + barcode);
        return jdbc.queryForObject("SELECT id FROM products WHERE user_id = ? AND sku_barcode = ?", Long.class, userId, barcode);
    }

    private static RequestPostProcessor as(long userId) {
        return jwt().jwt(claims -> claims.subject(Long.toString(userId)));
    }
}
