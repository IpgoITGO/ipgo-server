package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.entity.PlannedReceipt;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.repository.PlannedReceiptRepository;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.service.ReceiptMatchingService;
import com.ipgo.server.service.UploadCleaningService;
import com.ipgo.server.support.UploadTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ReceiptMatchingIntegrationTests extends DatabaseIntegrationTest {
    static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    @TempDir static Path root;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadFileRepository uploads;
    @Autowired PlannedReceiptRepository receipts;
    @Autowired UploadCleaningService cleaning;
    @Autowired ReceiptMatchingService matching;
    @Autowired com.ipgo.server.service.PlannedReceiptService plannedReceipts;
    @Autowired MockMvc mvc;
    long owner, warehouse, product;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("ipgo.upload.directory", () -> root.toString());
    }
    @BeforeEach void fixture() {
        UploadTestDatabase.clear(jdbc);
        owner = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        jdbc.update("INSERT INTO warehouses(user_id,name) VALUES (?,'기본 창고')", owner);
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id = ?", Long.class, owner);
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name) VALUES (?,'A','상품A')", owner);
        product = jdbc.queryForObject("SELECT id FROM products WHERE user_id = ?", Long.class, owner);
    }
    @AfterEach void cleanup() { UploadTestDatabase.clear(jdbc); }

    @Test void allocates160To100And50AndKeepsStableOwnerScopedU2Snapshot() throws Exception {
        long first = receipt(100), second = receipt(50);
        var upload = upload(); complete(upload, 160);
        assertThat(state(first)).isEqualTo("RECEIVED"); assertThat(state(second)).isEqualTo("RECEIVED");
        assertThat(report(upload).matchedReceipts()).extracting(r -> r.quantity()).containsExactly(100L, 50L);
        assertThat(report(upload).receiptMatching().status()).isEqualTo("EVALUATED");
        jdbc.update("UPDATE products SET sku_name = '바뀐이름' WHERE id = ?", product);
        mvc.perform(get("/api/uploads/{id}", upload.getId()).with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("matchedReceipts[0].skuName").value("상품A"))
                .andExpect(jsonPath("matchedReceipts[0].receivedOn").value("2026-09-01"))
                .andExpect(jsonPath("receiptMatching.status").value("EVALUATED"));
        long other = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        mvc.perform(get("/api/uploads/{id}", upload.getId()).with(jwt().jwt(j -> j.subject(""+other))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/uploads/{id}", upload.getId())).andExpect(status().isUnauthorized());
        assertThat(allocations()).isEqualTo(2);
    }
    @Test void quantity120OnlyCompletesFirstAndDoesNotCarry20ToNextDay() {
        long first = receipt(100), second = receipt(50);
        var upload = upload(); complete(upload, 120, 30);
        assertThat(state(first)).isEqualTo("RECEIVED"); assertThat(state(second)).isEqualTo("PENDING");
        assertThat(reasons(upload)).contains("INSUFFICIENT_QUANTITY"); assertThat(allocations()).isEqualTo(1);
    }
    @Test void quantity60DoesNotSkip100ToCompleteLater50() {
        long first = receipt(100), second = receipt(50); var upload = upload(); complete(upload, 60);
        assertThat(state(first)).isEqualTo("PENDING"); assertThat(state(second)).isEqualTo("PENDING");
        assertThat(allocations()).isZero();
    }
    @Test void differentDays40And60DoNotComplete100() {
        long id = receipt(100); complete(upload(), 40, 60);
        assertThat(state(id)).isEqualTo("PENDING"); assertThat(allocations()).isZero();
    }
    @Test void sumsLotsOnSameStockDateRatherThanLotReceivedDate() {
        long id = receipt(100); var upload = upload();
        var r = result(upload, 100);
        r = new CleaningResult(r.periodStart(), r.periodEnd(), r.asOfDate(), r.products(),
                List.of(new CleaningResult.Unit("LOT1", "A", DAY.minusDays(2)), new CleaningResult.Unit("LOT2", "A", DAY.minusDays(1))),
                List.of(new CleaningResult.UnitDay(upload.getId(), "LOT1", DAY.minusDays(2), DAY, 0L, 40L, 0L, 0L, 40L, "test", 1),
                        new CleaningResult.UnitDay(upload.getId(), "LOT2", DAY.minusDays(1), DAY, 0L, 60L, 0L, 0L, 60L, "test", 2)),
                r.productDailyStocks(), r.validation(), null);
        cleaning.complete(upload.getId(), upload.getJobId(), r);
        assertThat(state(id)).isEqualTo("RECEIVED");
        assertThat(report(upload).matchedReceipts().getFirst().receivedOn()).isEqualTo(DAY);
    }
    @Test void openingAndAdjustmentAreNotInbound() {
        long id = receipt(100); var upload = upload(); var r = result(upload, 0);
        r = new CleaningResult(DAY, DAY, DAY, r.products(), r.inventoryUnits(),
                List.of(new CleaningResult.UnitDay(upload.getId(), "LOT1", DAY, DAY, 100L, 0L, 0L, 100L, 200L, "test", 1)),
                List.of(new CleaningResult.ProductDay(upload.getId(), "A", DAY, 100L, 0L, 0L, 100L, 200L, true, null)), r.validation(), null);
        cleaning.complete(upload.getId(), upload.getJobId(), r);
        assertThat(state(id)).isEqualTo("PENDING");
    }
    @Test void excludesInvalidObservationAndLotAggregateMismatch() {
        long id = receipt(100); var upload = upload(); var r = result(upload, 100);
        cleaning.complete(upload.getId(), upload.getJobId(), invalid(r));
        assertThat(state(id)).isEqualTo("PENDING"); assertThat(reasons(upload)).contains("INVALID_OBSERVATION");
        var second = upload(); var other = result(second, 100);
        other = new CleaningResult(DAY, DAY, DAY, other.products(), other.inventoryUnits(),
                List.of(new CleaningResult.UnitDay(second.getId(), "LOT1", DAY, DAY, 0L, 99L, 0L, 0L, 99L, "test", 1)),
                other.productDailyStocks(), other.validation(), null);
        cleaning.complete(second.getId(), second.getJobId(), other);
        assertThat(state(id)).isEqualTo("PENDING"); assertThat(reasons(second)).contains("INVALID_OBSERVATION");
    }
    @Test void usesSeoulRegistrationDateAndExcludesNoDateCancelledAndLaterRegistrations() {
        long beforeMidnight = receipt(10), sameDay = receipt(10), later = receipt(10), noDate = receipt(10), cancelled = receipt(10);
        created(beforeMidnight, "2026-08-31T14:59:59Z"); created(sameDay, "2026-08-31T15:00:00Z");
        created(later, "2026-09-01T15:00:00Z");
        jdbc.update("UPDATE planned_receipts SET expected_on = NULL,status = 'NO_DATE' WHERE id = ?", noDate);
        jdbc.update("UPDATE planned_receipts SET status = 'CANCELLED' WHERE id = ?", cancelled);
        var upload = upload(); complete(upload, 100);
        assertThat(state(beforeMidnight)).isEqualTo("RECEIVED");
        for (long id : List.of(sameDay, later, noDate, cancelled)) assertThat(state(id)).isNotEqualTo("RECEIVED");
        assertThat(reasons(upload)).contains("REGISTRATION_DAY", "NO_EXPECTED_DATE");
    }
    @Test void sortsExpectedDateThenCreationTimeThenIdAndAllowsEarlyArrival() {
        long laterExpected = receipt(10), newer = receipt(10), older = receipt(10);
        jdbc.update("UPDATE planned_receipts SET expected_on = ? WHERE id = ?", DAY.plusDays(10), laterExpected);
        created(older, "2026-08-30T00:00:00Z"); created(newer, "2026-08-31T00:00:00Z");
        complete(upload(), 10);
        assertThat(state(older)).isEqualTo("RECEIVED"); assertThat(state(newer)).isEqualTo("PENDING");
        assertThat(state(laterExpected)).isEqualTo("PENDING");
    }
    @Test void identicalRevisionAndDuplicateMessageDoNotAllocateAgain() {
        long first = receipt(100), second = receipt(50); var upload = upload(); complete(upload, 120);
        assertThat(cleaning.complete(upload.getId(), upload.getJobId(), result(upload, 120)))
                .isEqualTo(UploadCleaningService.Outcome.ALREADY_FINISHED);
        var revision = upload(); complete(revision, 120);
        assertThat(allocations()).isEqualTo(1); assertThat(state(second)).isEqualTo("PENDING");
        assertThat(report(revision).matchedReceipts()).extracting(r -> r.receiptId()).containsExactly(first);
        assertThat(report(upload)).isEqualTo(report(revision));
    }
    @Test void changedAllocatedSourceKeepsOriginalAllocationAndStickyReviewEvenWhenRestored() {
        long first = receipt(100), second = receipt(50); var upload = upload(); complete(upload, 100);
        var original = report(upload);
        var changed = upload(); complete(changed, 200);
        assertThat(state(first)).isEqualTo("RECEIVED"); assertThat(state(second)).isEqualTo("PENDING");
        assertThat(reasons(changed)).containsExactly("SOURCE_CHANGED"); assertThat(allocations()).isEqualTo(1);
        var restored = upload(); complete(restored, 100);
        assertThat(reasons(restored)).containsExactly("SOURCE_CHANGED");
        assertThat(report(upload)).isEqualTo(original);
    }
    @Test void removedProductOrShortenedPeriodMarksAllocatedSourceForReview() {
        long id = receipt(100); var first = upload(); complete(first, 0, 100);
        var shortened = upload(); complete(shortened, 0);
        assertThat(state(id)).isEqualTo("RECEIVED"); assertThat(reasons(shortened)).contains("SOURCE_CHANGED");
        var removed = upload(); cleaning.complete(removed.getId(), removed.getJobId(), new CleaningResult(DAY, DAY, DAY,
                List.of(), List.of(), List.of(), List.of(), new CleaningResult.Validation(0, 0, List.of()), null));
        assertThat(reasons(removed)).contains("SOURCE_CHANGED"); assertThat(allocations()).isEqualTo(1);
    }
    @Test void unallocatedChangedSourceMayBeEvaluatedButUnchangedSourceCannotSpendRemainderAfterOrderEdit() {
        long id = receipt(100); var first = upload(); complete(first, 60);
        jdbc.update("UPDATE planned_receipts SET quantity = 50 WHERE id = ?", id);
        var same = upload(); complete(same, 60);
        assertThat(state(id)).isEqualTo("PENDING"); assertThat(reasons(same)).contains("PREVIOUSLY_EVALUATED");
        var changed = upload(); complete(changed, 70);
        assertThat(state(id)).isEqualTo("RECEIVED"); assertThat(allocations()).isEqualTo(1);
    }
    @Test void lateSupersededResultIsHistoricalOnly() {
        long id = receipt(100); var old = upload(); var current = upload();
        complete(old, 100); assertThat(state(id)).isEqualTo("PENDING");
        assertThat(report(old).receiptMatching().status()).isEqualTo("SUPERSEDED");
        complete(current, 100); assertThat(state(id)).isEqualTo("RECEIVED");
    }
    @Test void receivedReceiptCannotBePatchedOrCancelledAndForeignOwnerStillGets403() throws Exception {
        long id = receipt(100); complete(upload(), 100);
        mvc.perform(patch("/api/planned-receipts/{id}", id).with(jwt().jwt(j -> j.subject(""+owner)))
                .contentType("application/json").content("{\"quantity\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("RECEIPT_RECEIVED"));
        mvc.perform(delete("/api/planned-receipts/{id}", id).with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isConflict());
        long other = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        mvc.perform(delete("/api/planned-receipts/{id}", id).with(jwt().jwt(j -> j.subject(""+other))))
                .andExpect(status().isForbidden());
        assertThat(state(id)).isEqualTo("RECEIVED"); assertThat(allocations()).isEqualTo(1);
    }
    @Test void concurrentDuplicateCannotDoubleAllocate() throws Exception {
        long id = receipt(100); var upload = upload(); var r = result(upload, 100);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return cleaning.complete(upload.getId(), upload.getJobId(), r); });
            var b = executor.submit(() -> { start.await(); return cleaning.complete(upload.getId(), upload.getJobId(), r); });
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(UploadCleaningService.Outcome.APPLIED, UploadCleaningService.Outcome.ALREADY_FINISHED);
        }
        assertThat(state(id)).isEqualTo("RECEIVED"); assertThat(allocations()).isEqualTo(1);
    }
    @Test void concurrentRevisionsOnlyLatestCanAllocate() throws Exception {
        long id = receipt(100); var old = upload(); var current = upload();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); complete(old, 100); return true; });
            var b = executor.submit(() -> { start.await(); complete(current, 100); return true; });
            start.countDown(); assertThat(a.get(20, TimeUnit.SECONDS)).isTrue(); assertThat(b.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(state(id)).isEqualTo("RECEIVED"); assertThat(allocations()).isEqualTo(1);
        assertThat(report(old).receiptMatching().status()).isEqualTo("SUPERSEDED");
    }
    @Test void concurrentCancellationAndCleanHaveOneConsistentWinner() throws Exception {
        long id = receipt(100); var upload = upload(); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); complete(upload, 100); return true; });
            var b = executor.submit(() -> {
                start.await();
                try { plannedReceipts.cancel(owner, id); return "CANCELLED"; }
                catch (com.ipgo.server.exception.ProductException e) { return e.code(); }
            });
            start.countDown(); assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            String outcome = b.get(20, TimeUnit.SECONDS);
            if ("CANCELLED".equals(outcome)) {
                assertThat(state(id)).isEqualTo("CANCELLED"); assertThat(allocations()).isZero();
            } else {
                assertThat(outcome).isEqualTo("RECEIPT_RECEIVED");
                assertThat(state(id)).isEqualTo("RECEIVED"); assertThat(allocations()).isEqualTo(1);
            }
        }
    }

    @Test void crossTenantEventIsRejectedAndReportFailureRollsBackWholeClean() {
        long id = receipt(100); var upload = upload();
        // 정제 마지막의 보고서 저장 실패도 상품/수불/발주/배정을 함께 rollback해야 한다.
        jdbc.update("INSERT INTO receipt_matching_reports(upload_file_id,report) VALUES (?,CAST('{}' AS JSONB))", upload.getId());
        assertThatThrownBy(() -> complete(upload, 100)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(state(id)).isEqualTo("PENDING"); assertThat(allocations()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_daily_stocks", Long.class)).isZero();
        assertThat(uploads.findById(upload.getId()).orElseThrow().getStatus()).isEqualTo("PROCESSING");
        long other = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO receipt_matching_events(user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id) VALUES (?,?,?,?,100,TRUE,?)",
                other, warehouse, product, DAY, upload.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private long receipt(long qty) {
        var saved = receipts.save(new PlannedReceipt(product, warehouse, qty, DAY.plusDays(3)));
        created(saved.getId(), "2026-08-31T00:00:00Z"); return saved.getId();
    }
    private void created(long id, String date) {
        jdbc.update("UPDATE planned_receipts SET created_at = ? WHERE id = ?", OffsetDateTime.ofInstant(Instant.parse(date), ZoneOffset.UTC), id);
    }
    private UploadFile upload() {
        int revision = uploads.lastRevision(warehouse, DAY)+1;
        return uploads.save(new UploadFile(warehouse, "2026-09.xlsx", UUID.randomUUID().toString(), "a".repeat(64), revision,
                new UploadPeriod(YearMonth.from(DAY))));
    }
    private void complete(UploadFile upload, long... quantities) { cleaning.complete(upload.getId(), upload.getJobId(), result(upload, quantities)); }
    private String state(long id) { return receipts.findById(id).orElseThrow().getStatus(); }
    private long allocations() { return jdbc.queryForObject("SELECT COUNT(*) FROM receipt_matching_allocations", Long.class); }
    private ReceiptMatchingService.Report report(UploadFile upload) { return matching.report(upload.getId()); }
    private List<String> reasons(UploadFile upload) { return report(upload).receiptMatching().issues().stream().map(i -> i.reason()).toList(); }
    private CleaningResult result(UploadFile upload, long... quantities) {
        var units = new ArrayList<CleaningResult.UnitDay>(); var stocks = new ArrayList<CleaningResult.ProductDay>();
        long opening = 0;
        for (int i = 0; i < quantities.length; i++) {
            long closing = opening + quantities[i];
            units.add(new CleaningResult.UnitDay(upload.getId(), "LOT1", DAY, DAY.plusDays(i), opening, quantities[i], 0L, 0L, closing, "test", i+1));
            stocks.add(new CleaningResult.ProductDay(upload.getId(), "A", DAY.plusDays(i), opening, quantities[i], 0L, 0L, closing, true, null));
            opening = closing;
        }
        return new CleaningResult(DAY, DAY.plusDays(quantities.length-1), DAY.plusDays(quantities.length-1),
                List.of(new CleaningResult.CleanProduct("A", "상품A", 1000L, false)),
                List.of(new CleaningResult.Unit("LOT1", "A", DAY)), units, stocks,
                new CleaningResult.Validation(1, 1, List.of()), null);
    }
    private CleaningResult invalid(CleaningResult r) {
        return new CleaningResult(r.periodStart(), r.periodEnd(), r.asOfDate(), r.products(), r.inventoryUnits(), r.unitDailyRecords(),
                r.productDailyStocks(), new CleaningResult.Validation(1, 0, List.of(new CleaningResult.ExcludedSku("A", "상품A", DAY, "검산 제외"))), null);
    }
}
