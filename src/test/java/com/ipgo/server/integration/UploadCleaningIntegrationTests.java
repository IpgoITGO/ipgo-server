package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.service.UploadCleaningService;
import com.ipgo.server.support.UploadTestDatabase;
import com.ipgo.server.support.UploadTestFiles;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class UploadCleaningIntegrationTests extends DatabaseIntegrationTest {
    static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    @TempDir static Path root;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired UploadFileRepository uploads;
    @Autowired UploadCleaningService cleaning;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ipgo.upload.directory", () -> root.toString());
    }
    @AfterEach void clear() { UploadTestDatabase.clear(jdbc); }

    @Test void resultRequiresLoginAndOwnership() throws Exception {
        long owner = user(), other = user();
        UploadFile upload = upload(owner);
        mvc.perform(get("/api/uploads/{id}", upload.getId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/uploads/{id}", upload.getId()).with(jwt().jwt(j -> j.subject(""+other))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/uploads/9223372036854775807").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/uploads/{id}", upload.getId()).with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("PROCESSING"))
                .andExpect(jsonPath("asOfDate").doesNotExist()).andExpect(jsonPath("validation").doesNotExist())
                .andExpect(jsonPath("matchedReceipts").isEmpty()).andExpect(jsonPath("jobId").doesNotExist())
                .andExpect(jsonPath("storageKey").doesNotExist());
    }

    @Test void readsAiCamelCaseContractWithoutLosingBooleanFields() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/clean-result.json")) {
            var result = mapper.readValue(input, CleaningResult.class);
            assertThat(result.products().getFirst().isNew()).isTrue();
            assertThat(result.productDailyStocks().getFirst().isValidObservation()).isTrue();
            var upload = upload(user());
            var json = mapper.valueToTree(result).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) json.get("unitDailyRecords").get(0)).put("uploadFileId", upload.getId());
            ((tools.jackson.databind.node.ObjectNode) json.get("productDailyStocks").get(0)).put("uploadFileId", upload.getId());
            assertThat(cleaning.complete(upload.getId(), upload.getJobId(), mapper.treeToValue(json, CleaningResult.class)))
                    .isEqualTo(UploadCleaningService.Outcome.APPLIED);
        }
    }

    @Test void persistsAtomicResultAndExclusionsWithServerProductIds() throws Exception {
        long owner = user(); var upload = upload(owner);
        var result = result(upload.getId(), "TEST-A", true);
        assertThat(cleaning.complete(upload.getId(), upload.getJobId(), result)).isEqualTo(UploadCleaningService.Outcome.APPLIED);
        assertThat(count("daily_stock_records")).isEqualTo(1);
        assertThat(count("product_daily_stocks")).isEqualTo(1);
        long productId = jdbc.queryForObject("SELECT id FROM products WHERE user_id = ?", Long.class, owner);
        mvc.perform(get("/api/uploads/{id}", upload.getId()).with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("DONE"))
                .andExpect(jsonPath("periodEnd").value("2026-09-01"))
                .andExpect(jsonPath("asOfDate").value("2026-09-01"))
                .andExpect(jsonPath("validation.checkedSkuCount").value(1))
                .andExpect(jsonPath("validation.targetSkuCount").value(0))
                .andExpect(jsonPath("validation.excludedSkus[0].productId").value(productId))
                .andExpect(jsonPath("validation.excludedSkus[0].reason").value("재고 검산 불일치"))
                .andExpect(jsonPath("runId").doesNotExist()).andExpect(jsonPath("error").doesNotExist());
        assertThat(uploads.findById(upload.getId()).orElseThrow().getValidationReport().estimatedReviewCycleDays()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT closing_qty FROM product_daily_stocks", Long.class)).isEqualTo(8);
    }

    @Test void rejectsWrongJobAndCrossUploadSourceWithoutWrites() throws Exception {
        var upload = upload(user());
        assertThatThrownBy(() -> cleaning.complete(upload.getId(), "wrong-job", result(upload.getId(), "TEST-A", false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleaning.complete(upload.getId(), upload.getJobId(), result(upload.getId()+1, "TEST-A", false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertUnchanged(upload);
    }

    @Test void rejectsAnotherUsersResultSource() throws Exception {
        var ownerUpload = upload(user()); var otherUpload = upload(user());
        assertThatThrownBy(() -> cleaning.complete(ownerUpload.getId(), ownerUpload.getJobId(),
                result(otherUpload.getId(), "TEST-A", false))).isInstanceOf(IllegalArgumentException.class);
        assertUnchanged(ownerUpload);
    }

    @Test void rejectsDuplicateOrIncompleteRowsAndInvalidCounters() throws Exception {
        var upload = upload(user()); var base = result(upload.getId(), "TEST-A", false);
        var duplicate = new CleaningResult(base.periodStart(), base.periodEnd(), base.asOfDate(), base.products(),
                base.inventoryUnits(), base.unitDailyRecords(), List.of(base.productDailyStocks().getFirst(),
                base.productDailyStocks().getFirst()), base.validation(), null);
        assertThatThrownBy(() -> cleaning.complete(upload.getId(), upload.getJobId(), duplicate))
                .isInstanceOf(IllegalArgumentException.class);
        var invalid = new CleaningResult(base.periodStart(), base.periodEnd(), base.asOfDate(), base.products(),
                base.inventoryUnits(), base.unitDailyRecords(), base.productDailyStocks(),
                new CleaningResult.Validation(10, 9, List.of()), null);
        assertThatThrownBy(() -> cleaning.complete(upload.getId(), upload.getJobId(), invalid))
                .isInstanceOf(IllegalArgumentException.class);
        var incomplete = new CleaningResult(base.periodStart(), base.periodEnd().plusDays(1), base.asOfDate().plusDays(1),
                base.products(), base.inventoryUnits(), base.unitDailyRecords(), base.productDailyStocks(), base.validation(), null);
        assertThatThrownBy(() -> cleaning.complete(upload.getId(), upload.getJobId(), incomplete))
                .isInstanceOf(IllegalArgumentException.class);
        assertUnchanged(upload);
    }

    @Test void concurrentDuplicateResultsAreAppliedOnce() throws Exception {
        var upload = upload(user()); var result = result(upload.getId(), "TEST-A", false);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return cleaning.complete(upload.getId(), upload.getJobId(), result); });
            var second = executor.submit(() -> { start.await(); return cleaning.complete(upload.getId(), upload.getJobId(), result); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(UploadCleaningService.Outcome.APPLIED, UploadCleaningService.Outcome.ALREADY_FINISHED);
        }
        assertThat(count("daily_stock_records")).isEqualTo(1);
        assertThat(count("product_daily_stocks")).isEqualTo(1);
        assertThat(cleaning.fail(upload.getId(), upload.getJobId(), UploadCleaningService.Failure.ENGINE_UNAVAILABLE))
                .isEqualTo(UploadCleaningService.Outcome.ALREADY_FINISHED);
        assertThat(uploads.findById(upload.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
    }

    @Test void failedResultCanBeQueriedAndLateSuccessDoesNotChangeIt() throws Exception {
        long owner = user(); var upload = upload(owner);
        cleaning.fail(upload.getId(), upload.getJobId(), UploadCleaningService.Failure.ENGINE_UNAVAILABLE);
        assertThat(cleaning.complete(upload.getId(), upload.getJobId(), result(upload.getId(), "TEST-A", false)))
                .isEqualTo(UploadCleaningService.Outcome.ALREADY_FINISHED);
        mvc.perform(get("/api/uploads/{id}", upload.getId()).with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("FAILED"))
                .andExpect(jsonPath("error.code").value("ENGINE_UNAVAILABLE"))
                .andExpect(jsonPath("asOfDate").doesNotExist()).andExpect(jsonPath("validation").doesNotExist());
        assertThat(count("products")).isZero();
        // 실패한 파일은 같은 기간을 새 revision으로 다시 올릴 수 있다.
        assertThat(upload(owner).getRevisionNo()).isEqualTo(2);
    }

    @Test void rejectsLotReassignmentAndRollsBackNewProducts() throws Exception {
        long owner = user(); var first = upload(owner);
        cleaning.complete(first.getId(), first.getJobId(), result(first.getId(), "TEST-A", false));
        var second = upload(owner);
        assertThatThrownBy(() -> cleaning.complete(second.getId(), second.getJobId(), result(second.getId(), "TEST-B", false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("products")).isEqualTo(1);
        assertThat(count("inventory_units")).isEqualTo(1);
        assertThat(count("daily_stock_records")).isEqualTo(1);
        assertThat(uploads.findById(second.getId()).orElseThrow().getStatus()).isEqualTo("PROCESSING");
    }

    @Test void revisionsPreserveHistoryAndUserEditedProduct() throws Exception {
        long owner = user(); var first = upload(owner);
        cleaning.complete(first.getId(), first.getJobId(), result(first.getId(), "TEST-A", false));
        jdbc.update("UPDATE products SET sku_name = '수정한 이름', unit_cost = 1234, is_active = false WHERE user_id = ?", owner);
        var second = upload(owner);
        cleaning.complete(second.getId(), second.getJobId(), result(second.getId(), "TEST-A", false));
        assertThat(count("products")).isEqualTo(1);
        assertThat(count("daily_stock_records")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sku_name FROM products WHERE user_id = ?", String.class, owner)).isEqualTo("수정한 이름");
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM products WHERE user_id = ?", Long.class, owner)).isEqualTo(1234);
        assertThat(jdbc.queryForObject("SELECT is_active FROM products WHERE user_id = ?", Boolean.class, owner)).isFalse();
    }

    @Test void emptyResultRemainsValidButNullNestedValuesAreRejected() throws Exception {
        var upload = upload(user());
        var invalid = new CleaningResult(DAY, DAY, DAY, null, List.of(), List.of(), List.of(),
                new CleaningResult.Validation(0, 0, List.of()), null);
        assertThatThrownBy(() -> cleaning.complete(upload.getId(), upload.getJobId(), invalid)).isInstanceOf(IllegalArgumentException.class);
        var empty = new CleaningResult(DAY, DAY, DAY, List.of(), List.of(), List.of(), List.of(),
                new CleaningResult.Validation(0, 0, List.of()), null);
        assertThat(cleaning.complete(upload.getId(), upload.getJobId(), empty)).isEqualTo(UploadCleaningService.Outcome.APPLIED);
    }

    private CleaningResult result(long id, String barcode, boolean excluded) {
        return new CleaningResult(DAY, DAY, DAY,
                List.of(new CleaningResult.CleanProduct(barcode, "테스트 상품", 1000L, true)),
                List.of(new CleaningResult.Unit("LOT-1", barcode, DAY)),
                List.of(new CleaningResult.UnitDay(id, "LOT-1", DAY, DAY, 10L, 0L, 2L, 0L, 8L, "재고변동", 5)),
                List.of(new CleaningResult.ProductDay(id, barcode, DAY, 10L, 0L, 2L, 0L, 8L, true, null)),
                new CleaningResult.Validation(1, excluded ? 0 : 1, excluded
                        ? List.of(new CleaningResult.ExcludedSku(barcode, "테스트 상품", DAY, "재고 검산 불일치")) : List.of()), 7);
    }
    private void assertUnchanged(UploadFile upload) {
        assertThat(count("products")).isZero();
        assertThat(count("daily_stock_records")).isZero();
        assertThat(uploads.findById(upload.getId()).orElseThrow().getStatus()).isEqualTo("PROCESSING");
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table, Integer.class); }
    private long user() { return UploadTestDatabase.newUser(jdbc, "clean-"+UUID.randomUUID()+"@example.test"); }
    private UploadFile upload(long owner) throws Exception {
        var response = mvc.perform(multipart("/api/uploads")
                .file(new MockMultipartFile("file", "2026-09.xlsx", "application/octet-stream", UploadTestFiles.workbook()))
                .param("overwrite", "true").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return uploads.findById(mapper.readTree(response).get("uploadId").longValue()).orElseThrow();
    }
}
