package com.ipgo.server.integration;

import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.service.EngineJobs;
import com.ipgo.server.service.EngineCalculationService;
import com.ipgo.server.service.EngineResults;
import com.ipgo.server.service.UploadRecordService;
import com.ipgo.server.support.UploadTestDatabase;
import com.ipgo.server.support.UploadTestFiles;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class EngineRealFlowIntegrationTests {
    @TempDir static Path root;
    @Autowired JdbcTemplate jdbc;
    @Autowired EngineJobs jobs;
    @Autowired EngineResults results;
    @Autowired EngineCalculationService calculations;
    @Autowired com.ipgo.server.service.CalculationRunService calculationStatus;
    @Autowired com.ipgo.server.service.DashboardService dashboard;
    @Autowired UploadRecordService records;
    @Autowired UploadFileRepository uploads;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper mapper;
    @Autowired com.ipgo.server.service.ProductDetailService details;
    @Autowired com.ipgo.server.service.ScenarioService scenarioService;
    static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    @DynamicPropertySource static void engineProperties(DynamicPropertyRegistry r) {
        if ("true".equals(System.getenv("IPGO_ENGINE_LOCAL_POSTGRES"))) {
            // This opt-in is deliberately pinned to the disposable Docker network name.
            r.add("spring.datasource.url", () -> "jdbc:postgresql://ipgo-engine-e2e-118-test-db:5432/engine_test");
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("IPGO_TEST_DB_USERNAME", "engine_test"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("IPGO_TEST_DB_PASSWORD", ""));
        } else {
            r.add("spring.datasource.url", () -> "jdbc:h2:mem:engine-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        }
        r.add("spring.flyway.target", () -> "11");
        r.add("ipgo.auth.jwt-secret", () -> "c3ludGhldGljLXRlc3Qta2V5LTMyaXRlbXMtb25seSE=");
        r.add("ipgo.engine.enabled", () -> "true");
        r.add("ipgo.engine.transport-enabled", () -> "false");
        r.add("ipgo.upload.directory", () -> root.toString());
    }
    @BeforeEach void schemaAndCleanup() {
        UploadTestDatabase.receiptMatchingSchema(jdbc);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'engine_jobs'", Integer.class) == 0) jdbc.execute("""
            CREATE TABLE engine_jobs (job_id VARCHAR(100) PRIMARY KEY, job_type VARCHAR(20) NOT NULL,
              target_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, payload JSONB NOT NULL, status VARCHAR(20) NOT NULL,
              attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
              created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at TIMESTAMP WITH TIME ZONE)
            """);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = 'calculation_runs' AND LOWER(column_name) = 'input_hash'", Integer.class) == 0)
            jdbc.execute("ALTER TABLE calculation_runs ADD COLUMN input_hash VARCHAR(64)");
        jdbc.update("DELETE FROM engine_jobs");
        UploadTestDatabase.clear(jdbc);
    }
    @AfterEach void cleanUp() { jdbc.update("DELETE FROM engine_jobs"); UploadTestDatabase.clear(jdbc); }
    private long user() { return UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test"); }
    private MockMultipartFile file() throws Exception {
        return new MockMultipartFile("file", "2026-09-01.xlsx", "application/octet-stream", UploadTestFiles.workbook());
    }
    private UploadFile upload(long owner) throws Exception {
        return uploads.findById(records.upload(owner, file(), true).uploadId()).orElseThrow();
    }
    private CleaningResult clean(long uploadId) {
        return new CleaningResult(DAY, DAY, DAY,
            List.of(new CleaningResult.CleanProduct("SKU-A", "Test", 100L, true)),
            List.of(new CleaningResult.Unit("LOT-1", "SKU-A", DAY)),
            List.of(new CleaningResult.UnitDay(uploadId, "LOT-1", DAY, DAY, 10L, 0L, 2L, 0L, 8L, "재고변동", 5)),
            List.of(new CleaningResult.ProductDay(uploadId, "SKU-A", DAY, 10L, 0L, 2L, 0L, 8L, true, null)),
            new CleaningResult.Validation(1, 1, List.of()), 7);
    }
    private String success(UploadFile u, CleaningResult result) {
        return mapper.writeValueAsString(Map.of("contractVersion", "1", "jobId", u.getJobId(),
                "jobType", "CLEAN", "status", "SUCCEEDED", "result", result));
    }
    private String failure(UploadFile u) {
        return mapper.writeValueAsString(Map.of("contractVersion", "1", "jobId", u.getJobId(),
                "jobType", "CLEAN", "status", "FAILED", "error", Map.of("code", "INVALID_SOURCE")));
    }
    private void faultDdl(String sql) throws java.sql.SQLException {
        if ("true".equals(System.getenv("IPGO_ENGINE_LOCAL_POSTGRES"))
                && "ipgo_server".equals(System.getenv("IPGO_TEST_DB_USERNAME"))) {
            // 최소 권한 서버 검사의 격리 Docker 고정 별칭에만 DDL 장애 fixture를 설치한다. 앱은 ipgo_server 권한을 유지한다.
            try (var connection = java.sql.DriverManager.getConnection(
                    "jdbc:postgresql://ipgo-engine-e2e-118-test-db:5432/engine_test", "ipgo_migrator", "");
                    var statement = connection.createStatement()) { statement.execute(sql); }
        } else jdbc.execute(sql);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private record ExclusionRun(long owner, UploadFile upload, long normal, long excluded, long inactive,
                                long runId, String jobId) { }

    private ExclusionRun exclusionRun() throws Exception {
        long owner = user(); var u = upload(owner);
        var barcodes = List.of("SKU-A", "ACTIVE-EXCLUDED", "INACTIVE-EXCLUDED");
        var cleaned = new CleaningResult(DAY, DAY, DAY,
                barcodes.stream().map(b -> new CleaningResult.CleanProduct(b, b, 100L, true)).toList(),
                barcodes.stream().map(b -> new CleaningResult.Unit(b, b, DAY)).toList(),
                barcodes.stream().map(b -> new CleaningResult.UnitDay(u.getId(), b, DAY, DAY,
                        10L, 0L, 2L, 0L, 8L, "재고변동", 5)).toList(),
                barcodes.stream().map(b -> new CleaningResult.ProductDay(u.getId(), b, DAY,
                        10L, 0L, 2L, 0L, 8L, "SKU-A".equals(b), null)).toList(),
                new CleaningResult.Validation(3, 1, List.of(
                        new CleaningResult.ExcludedSku("ACTIVE-EXCLUDED", "ACTIVE-EXCLUDED", DAY, "활성 상품 검산 불일치"),
                        new CleaningResult.ExcludedSku("INACTIVE-EXCLUDED", "INACTIVE-EXCLUDED", DAY, "비활성 상품 검산 불일치"))), 7);
        results.accept(success(u, cleaned));
        var ids = jdbc.queryForList("SELECT id FROM products WHERE user_id=? ORDER BY sku_barcode", Long.class, owner);
        long excluded = ids.get(0), inactive = ids.get(1), normal = ids.get(2);
        jdbc.update("UPDATE products SET is_active=FALSE WHERE id=?", inactive);
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5,default_review_cycle_days=7,default_order_unit_qty=1 WHERE id=?", u.getWarehouseId());
        long run = calculations.request(owner, u.getWarehouseId(), "SETTINGS", false);
        String job = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id=?", String.class, run);
        return new ExclusionRun(owner, u, normal, excluded, inactive, run, job);
    }

    private Map<String,Object> validCalculation(long product) {
        var scenarios = List.of("P80", "P100", "P120").stream().map(type -> Map.of(
                "type", type, "qty", 25, "expectedEndingStock", 0, "unmetDemandQty", 0,
                "isDuplicate", false, "dailyProjection", List.of())).toList();
        return Map.of("productId", product, "status", "OK", "recommendedQty", 25,
                "recommendedAmount", 2500, "horizonDays", 12, "scenarios", scenarios);
    }

    private String calculationSuccess(String job, List<Map<String,Object>> calculated) {
        return mapper.writeValueAsString(Map.of("contractVersion", "1", "jobId", job,
                "jobType", "CALCULATE", "status", "SUCCEEDED",
                "result", Map.of("engineVersion", "test", "results", calculated)));
    }

    @Test void inactiveValidationExclusionDoesNotBlockActiveResults() throws Exception {
        var f = exclusionRun();
        assertThat(jobs.find(f.jobId()).payload().path("productIds").toString()).isEqualTo("["+f.normal()+"]");
        String response = calculationSuccess(f.jobId(), List.of(validCalculation(f.normal())));
        // Before #105 this throws after writing the normal result and rolls the whole transaction back.
        results.accept(response);
        results.accept(response);
        assertThat(jobs.find(f.jobId()).status()).isEqualTo("DONE");
        assertThat(calculationStatus.status(f.owner(), f.runId()).status()).isEqualTo("DONE");
        assertThat(count("calculation_results")).isEqualTo(2);
        assertThat(count("scenario_results")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM calculation_results WHERE run_id=? AND product_id=?",
                String.class, f.runId(), f.normal())).isEqualTo("OK");
        var excluded = jdbc.queryForMap("SELECT status,reason FROM calculation_results WHERE run_id=? AND product_id=?",
                f.runId(), f.excluded());
        assertThat(excluded.get("status")).isEqualTo("EXCLUDED");
        assertThat(excluded.get("reason")).isEqualTo("활성 상품 검산 불일치");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_results WHERE product_id=?", Integer.class, f.inactive())).isZero();
        var snapshot = mapper.readTree(jdbc.queryForObject(
                "SELECT selection_snapshot FROM calculation_runs WHERE id=?", String.class, f.runId()));
        if (snapshot.isString()) snapshot = mapper.readTree(snapshot.asText());
        assertThat(snapshot.path("excluded").size()).isEqualTo(1);
        assertThat(snapshot.path("excluded").path(""+f.excluded()).asText()).isEqualTo("활성 상품 검산 불일치");
        assertThat(snapshot.path("productIds").toString()).doesNotContain(""+f.inactive());
        assertThat(uploads.findById(f.upload().getId()).orElseThrow().getValidationReport().validation().excludedSkus()).hasSize(2);
    }

    @Test void resultStorageFailureCanRetryTheSameMessageWithoutPartialOrDuplicateRows() throws Exception {
        var f = exclusionRun();
        String response = calculationSuccess(f.jobId(), List.of(validCalculation(f.normal())));
        faultDdl("ALTER TABLE scenario_results ADD CONSTRAINT ck_test_result_retry CHECK (scenario_qty < 0)");
        try {
            assertThatThrownBy(() -> results.accept(response))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_results WHERE run_id=?", Long.class, f.runId())).isZero();
            assertThat(count("scenario_results")).isZero();
            assertThat(jobs.find(f.jobId()).status()).isEqualTo("PENDING");
            assertThat(calculationStatus.status(f.owner(), f.runId()).status()).isEqualTo("QUEUED");
        } finally {
            faultDdl("ALTER TABLE scenario_results DROP CONSTRAINT ck_test_result_retry");
        }
        results.accept(response);
        results.accept(response);
        assertThat(jobs.find(f.jobId()).status()).isEqualTo("DONE");
        assertThat(calculationStatus.status(f.owner(), f.runId()).status()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_results WHERE run_id=?", Long.class, f.runId())).isEqualTo(2);
        assertThat(count("scenario_results")).isEqualTo(3);
    }

    @Test void concurrentSettingsAndReceiptChangeKeepOldAndNewCalculationSnapshotsSeparate() throws Exception {
        var f = exclusionRun();
        var original = jobs.find(f.jobId()).payload();
        String response = calculationSuccess(f.jobId(), List.of(validCalculation(f.normal())));
        var started = new java.util.concurrent.CountDownLatch(1);
        long[] nextRun = {0};
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.CompletableFuture<?>[] pending = {null};
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM warehouses WHERE id=? FOR UPDATE", Long.class, f.upload().getWarehouseId());
                pending[0] = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    started.countDown();
                    results.accept(response);
                }, executor);
                try {
                    if (!started.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("result thread did not start");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                }
                jdbc.update("UPDATE warehouses SET default_order_unit_qty=5 WHERE id=?", f.upload().getWarehouseId());
                jdbc.update("INSERT INTO planned_receipts(product_id,warehouse_id,expected_on,quantity,status,created_at,updated_at) VALUES (?,?,?,?, 'PENDING', CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        f.normal(), f.upload().getWarehouseId(), DAY.plusDays(3), 9);
                nextRun[0] = calculations.request(f.owner(), f.upload().getWarehouseId(), "RECEIPTS", false);
            });
            pending[0].get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(nextRun[0]).isNotEqualTo(f.runId());
        assertThat(jobs.find(f.jobId()).payload()).isEqualTo(original);
        assertThat(calculationStatus.status(f.owner(), f.runId()).status()).isEqualTo("DONE");
        assertThat(calculationStatus.status(f.owner(), nextRun[0]).status()).isEqualTo("QUEUED");
        String nextJob = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id=?", String.class, nextRun[0]);
        var nextInput = jobs.find(nextJob).payload();
        assertThat(nextInput.path("settings").get(0).path("orderUnitQty").asLong()).isEqualTo(5);
        assertThat(nextInput.path("plannedReceipts").get(0).path("quantity").asLong()).isEqualTo(9);
        assertThat(original.path("plannedReceipts").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_results WHERE run_id=?", Long.class, nextRun[0])).isZero();
    }

    @Test void cancelStopsResendAndIgnoresLateResult() throws Exception {
        var f = exclusionRun();
        assertThatThrownBy(() -> calculationStatus.cancel(user(), f.runId()))
                .isInstanceOfSatisfying(com.ipgo.server.exception.ProductException.class,
                        e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
        var canceled = calculationStatus.cancel(f.owner(), f.runId());
        assertThat(canceled.status()).isEqualTo("FAILED");
        assertThat(canceled.errorMessage()).isEqualTo(com.ipgo.server.service.CalculationRunService.CANCELED_MESSAGE);
        assertThat(canceled.completedAt()).isNotNull();
        // The outbox job ends too, so the dispatcher never resends it.
        assertThat(jobs.find(f.jobId()).status()).isEqualTo("FAILED");
        assertThat(jobs.due()).noneMatch(j -> j.id().equals(f.jobId()));
        assertThat(jobs.expired()).doesNotContain(f.jobId());
        // A late engine success for the canceled job is acknowledged but stores nothing.
        results.accept(calculationSuccess(f.jobId(), List.of(validCalculation(f.normal()))));
        assertThat(count("calculation_results")).isZero();
        assertThat(count("scenario_results")).isZero();
        assertThat(calculationStatus.status(f.owner(), f.runId()).status()).isEqualTo("FAILED");
        assertThatThrownBy(() -> calculationStatus.cancel(f.owner(), f.runId()))
                .isInstanceOfSatisfying(com.ipgo.server.exception.ProductException.class,
                        e -> assertThat(e.code()).isEqualTo("CALCULATION_FINISHED"));
        // The next request starts a fresh run instead of reusing the canceled one.
        assertThat(calculations.request(f.owner(), f.upload().getWarehouseId(), "SETTINGS", false)).isNotEqualTo(f.runId());
    }

    @Test void calculationResendsAfterFiveMinutesAndExpiresAfterFifteenButCleanKeepsLongerWindow() throws Exception {
        var f = exclusionRun();
        var cleaning = upload(user());
        assertThat(jobs.claim(f.jobId())).isTrue(); jobs.sent(f.jobId());
        assertThat(jobs.claim(cleaning.getJobId())).isTrue(); jobs.sent(cleaning.getJobId());
        var now = java.time.Instant.now();
        var calcNext = jdbc.queryForObject("SELECT next_attempt_at FROM engine_jobs WHERE job_id=?", java.sql.Timestamp.class, f.jobId()).toInstant();
        var cleanNext = jdbc.queryForObject("SELECT next_attempt_at FROM engine_jobs WHERE job_id=?", java.sql.Timestamp.class, cleaning.getJobId()).toInstant();
        assertThat(calcNext).isBetween(now.plusSeconds(240), now.plusSeconds(360));
        assertThat(cleanNext).isBetween(now.plusSeconds(1140), now.plusSeconds(1260));

        var sixteenMinutesAgo = java.sql.Timestamp.from(now.minusSeconds(16 * 60));
        jdbc.update("UPDATE engine_jobs SET created_at=? WHERE job_id IN (?,?)", sixteenMinutesAgo, f.jobId(), cleaning.getJobId());
        assertThat(jobs.expired()).contains(f.jobId()).doesNotContain(cleaning.getJobId());
        results.expire(f.jobId());
        var expired = calculationStatus.status(f.owner(), f.runId());
        assertThat(expired.status()).isEqualTo("FAILED");
        assertThat(expired.errorMessage()).isEqualTo(com.ipgo.server.service.EngineResultWriter.TIMEOUT_MESSAGE);
        assertThat(jobs.find(f.jobId()).status()).isEqualTo("FAILED");
        assertThat(jobs.find(cleaning.getJobId()).status()).isEqualTo("SENT");
    }

    @Test void outOfSelectionAndOtherOwnerResultsStillRollback() throws Exception {
        var f = exclusionRun();
        long other = user();
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name,unit_cost,is_active) VALUES (?,?,?,100,TRUE)", other, "OTHER", "Other");
        long foreign = jdbc.queryForObject("SELECT id FROM products WHERE user_id=?", Long.class, other);
        for (long outside : List.of(f.inactive(), foreign)) {
            String response = calculationSuccess(f.jobId(), List.of(validCalculation(f.normal()), validCalculation(outside)));
            assertThatThrownBy(() -> results.accept(response)).isInstanceOf(IllegalArgumentException.class);
            assertThat(count("calculation_results")).isZero();
            assertThat(count("scenario_results")).isZero();
            assertThat(count("ai_explanations")).isZero();
            assertThat(calculationStatus.status(f.owner(), f.runId()).status()).isEqualTo("QUEUED");
            assertThat(jobs.find(f.jobId()).status()).isEqualTo("PENDING");
        }
    }

    @Test void uploadAndOutboxCommitOrRollbackTogether() throws Exception {
        long owner = user(); var multipart = file();
        tx.executeWithoutResult(s -> { records.upload(owner, multipart, true); s.setRollbackOnly(); });
        assertThat(count("upload_files")).isZero();
        assertThat(jobs.due()).isEmpty();
        var saved = upload(owner);
        assertThat(jobs.due()).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo(saved.getJobId());
            assertThat(job.payload().path("uploadId").asLong()).isEqualTo(saved.getId());
        });
    }
    @Test void leaseCrashAndResendKeepIdentity() throws Exception {
        var u = upload(user()); String id = u.getJobId();
        assertThat(jobs.claim(id)).isTrue();
        assertThat(jobs.claim(id)).isFalse();
        assertThat(jobs.due()).isEmpty();
        jdbc.update("UPDATE engine_jobs SET next_attempt_at = CURRENT_TIMESTAMP WHERE job_id = ?", id);
        assertThat(jobs.due()).singleElement().satisfies(j -> assertThat(j.id()).isEqualTo(id));
        assertThat(jobs.claim(id)).isTrue(); jobs.sent(id);
        assertThat(jobs.find(id).status()).isEqualTo("SENT");
        assertThat(jobs.due()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempts FROM engine_jobs WHERE job_id = ?", Integer.class, id)).isEqualTo(2);
        jdbc.update("UPDATE engine_jobs SET next_attempt_at = CURRENT_TIMESTAMP WHERE job_id = ?", id);
        assertThat(jobs.due()).singleElement().satisfies(j -> assertThat(j.id()).isEqualTo(id));
    }
    @Test void cleanResultIsAtomicAndLateFailureCannotOverwrite() throws Exception {
        var u = upload(user());
        assertThatThrownBy(() -> results.accept(success(u, clean(u.getId()+1)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("products")).isZero();
        assertThat(jobs.find(u.getJobId()).status()).isEqualTo("PENDING");
        results.accept(success(u, clean(u.getId())));
        results.accept(success(u, clean(u.getId())));
        results.accept(failure(u));
        assertThat(count("daily_stock_records")).isEqualTo(1);
        assertThat(jobs.find(u.getJobId()).status()).isEqualTo("DONE");
        assertThat(uploads.findById(u.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
    }
    @Test void compressedResultIsAtomicIdempotentAndCorruptionCannotWrite() throws Exception {
        var u = upload(user());
        String raw = success(u, clean(u.getId()));
        byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var buffer = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(buffer)) { gzip.write(bytes); }
        var wrapper = new java.util.HashMap<String, Object>();
        wrapper.put("contractVersion", "2"); wrapper.put("encoding", "gzip+base64");
        wrapper.put("uncompressedBytes", bytes.length);
        wrapper.put("payload", java.util.Base64.getEncoder().encodeToString(buffer.toByteArray()));
        wrapper.put("sha256", "corrupt");
        assertThatThrownBy(() -> results.accept(mapper.writeValueAsString(wrapper)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("products")).isZero();
        assertThat(jobs.find(u.getJobId()).status()).isEqualTo("PENDING");
        wrapper.put("sha256", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        String wire = mapper.writeValueAsString(wrapper);
        results.accept(wire); results.accept(wire);
        assertThat(count("daily_stock_records")).isEqualTo(1);
        assertThat(jobs.find(u.getJobId()).status()).isEqualTo("DONE");
    }

    @Test void failureAndExpiryAreTerminal() throws Exception {
        var first = upload(user()); results.accept(failure(first));
        assertThat(jobs.find(first.getJobId()).status()).isEqualTo("FAILED");
        assertThat(uploads.findById(first.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        var second = upload(user());
        jdbc.update("UPDATE engine_jobs SET created_at = ? WHERE job_id = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(7201)), second.getJobId());
        assertThat(jobs.expired()).contains(second.getJobId());
        results.expire(second.getJobId()); results.accept(success(second, clean(second.getId())));
        assertThat(jobs.find(second.getJobId()).status()).isEqualTo("FAILED");
        assertThat(uploads.findById(second.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(count("products")).isZero();
    }
    @Test void inboundMatchingCommitsBeforeCalculationSnapshotAndRemovesFutureReceipt() throws Exception {
        long owner = user(); var u = upload(owner);
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5,default_review_cycle_days=7,default_order_unit_qty=1 WHERE id=?", u.getWarehouseId());
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name) VALUES (?,'SKU-A','Test')", owner);
        long product = jdbc.queryForObject("SELECT id FROM products WHERE user_id=?", Long.class, owner);
        jdbc.update("INSERT INTO planned_receipts(product_id,warehouse_id,expected_on,quantity,status,created_at,updated_at) VALUES (?,?,?,5,'PENDING',?,CURRENT_TIMESTAMP)",
                product, u.getWarehouseId(), DAY.plusDays(4), java.time.OffsetDateTime.parse("2026-08-31T00:00:00Z"));
        var original = clean(u.getId());
        var inbound = new CleaningResult(DAY, DAY, DAY, original.products(), original.inventoryUnits(),
                List.of(new CleaningResult.UnitDay(u.getId(), "LOT-1", DAY, DAY, 0L, 5L, 0L, 0L, 5L, "test", 1)),
                List.of(new CleaningResult.ProductDay(u.getId(), "SKU-A", DAY, 0L, 5L, 0L, 0L, 5L, true, null)), original.validation(), 7);
        results.accept(success(u, inbound));
        assertThat(jdbc.queryForObject("SELECT status FROM planned_receipts WHERE product_id=?", String.class, product)).isEqualTo("RECEIVED");
        var calculate = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE warehouse_id=?", String.class, u.getWarehouseId());
        assertThat(jobs.find(calculate).payload().path("plannedReceipts").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt_matching_allocations", Integer.class)).isEqualTo(1);
    }

    @Test void cleanEnqueuesCalculationAndWriterCommitsOnlyValidCompleteResults() throws Exception {
        long owner = user(); var u = upload(owner);
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5, default_review_cycle_days=7, default_order_unit_qty=1 WHERE id=?", u.getWarehouseId());
        results.accept(success(u, clean(u.getId())));
        assertThat(jobs.find(u.getJobId()).status()).isEqualTo("DONE");
        assertThat(count("calculation_runs")).isEqualTo(1);
        long run = jdbc.queryForObject("SELECT id FROM calculation_runs", Long.class);
        String jobId = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id=?", String.class, run);
        var request = jobs.find(jobId).payload();
        long product = request.path("productIds").get(0).asLong();
        assertThat(request.path("userId").asLong()).isEqualTo(owner);
        assertThat(request.path("uploadFileIds").get(0).asLong()).isEqualTo(u.getId());
        assertThat(request.path("settings").get(0).path("unitCost").asLong()).isEqualTo(100);
        assertThatThrownBy(() -> calculations.request(owner+99999, u.getWarehouseId(), "SETTINGS", false))
                .isInstanceOf(IllegalArgumentException.class);
        var scenarios = List.of(
            Map.of("type", "P80", "qty", 20, "expectedEndingStock", 0, "unmetDemandQty", 5, "isDuplicate", false, "dailyProjection", List.of()),
            Map.of("type", "P100", "qty", 25, "expectedEndingStock", 0, "unmetDemandQty", 0, "isDuplicate", false, "dailyProjection", List.of()),
            Map.of("type", "P120", "qty", 30, "expectedEndingStock", 5, "unmetDemandQty", 0, "isDuplicate", false, "dailyProjection", List.of()));
        var good = Map.of("productId", product, "status", "OK", "recommendedQty", 25, "recommendedAmount", 2500, "horizonDays", 12, "requiredQtyBeforeRounding", 25.0, "scenarios", scenarios, "riskBasis", "POOLED", "riskSampleSize", 20, "preArrivalRisk", 0.1);
        var bad = Map.of("productId", product, "status", "OK", "recommendedQty", 25, "horizonDays", 12, "scenarios", scenarios.subList(0, 2));
        java.util.function.Function<Object,String> envelope = result -> mapper.writeValueAsString(Map.of("contractVersion", "1", "jobId", jobId, "jobType", "CALCULATE", "status", "SUCCEEDED", "result", Map.of("engineVersion", "test", "results", List.of(result))));
        assertThatThrownBy(() -> results.accept(envelope.apply(bad))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("calculation_results")).isZero();
        assertThat(count("scenario_results")).isZero();
        assertThat(count("ai_explanations")).isZero();
        var wrong = Map.of("productId", product+99999, "status", "OK", "recommendedQty", 25, "horizonDays", 12, "scenarios", scenarios);
        assertThatThrownBy(() -> results.accept(envelope.apply(wrong))).isInstanceOf(IllegalArgumentException.class);
        var badBasis = new java.util.HashMap<String,Object>(good);
        badBasis.put("riskBasis", "UNKNOWN");
        assertThatThrownBy(() -> results.accept(envelope.apply(badBasis))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("calculation_results")).isZero();
        results.accept(envelope.apply(good)); results.accept(envelope.apply(good));
        assertThat(count("calculation_results")).isEqualTo(1);
        assertThat(count("scenario_results")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM calculation_runs WHERE id=?", String.class, run)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT mode FROM ai_explanations", String.class)).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_explanations", String.class)).isEqualTo("DISABLED");
        assertThat(jobs.find(jobId).status()).isEqualTo("DONE");
        assertThat(records.result(owner, u.getId()).runId()).isEqualTo(run);
        assertThat(details.detail(owner, product, null, null).result().requiredQtyBeforeRounding()).isEqualTo(25);
        long resultId = jdbc.queryForObject("SELECT id FROM calculation_results", Long.class);
        assertThat(jdbc.queryForObject("SELECT risk_basis FROM calculation_results WHERE id=?", String.class, resultId))
                .isEqualTo("POOLED");
        assertThat(scenarioService.scenarios(owner, resultId).riskBasis()).isEqualTo("POOLED");
        Long forced = calculations.request(owner, u.getWarehouseId(), "RECALCULATE", true);
        assertThat(forced).isNotEqualTo(run);
        String nextJob = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id=?", String.class, forced);
        String invalidNext = mapper.writeValueAsString(Map.of("contractVersion", "1", "jobId", nextJob, "jobType", "CALCULATE", "status", "SUCCEEDED", "result", Map.of("engineVersion", "test", "results", List.of(bad))));
        assertThatThrownBy(() -> results.accept(invalidNext)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("calculation_results")).isEqualTo(1);
        assertThat(count("scenario_results")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM calculation_runs WHERE id=?", String.class, run)).isEqualTo("DONE");
        // A synthetic failure callback exercises server failure handling in isolated H2, not a real AI outage.
        results.accept(mapper.writeValueAsString(Map.of("contractVersion", "1", "jobId", nextJob,
                "jobType", "CALCULATE", "status", "FAILED", "error", Map.of("code", "ENGINE_UNAVAILABLE"))));
        assertThat(calculationStatus.status(owner, forced).status()).isEqualTo("FAILED");
        assertThat(calculationStatus.status(owner, forced).errorMessage()).isNotBlank();
        assertThat(dashboard.needs(owner, "shortage").runId()).isEqualTo(run);
        assertThat(dashboard.needs(owner, "shortage").resultStatus()).isEqualTo("STALE");
        assertThat(details.detail(owner, product, null, null).result().resultId()).isEqualTo(resultId);
        assertThat(count("calculation_results")).isEqualTo(1);
        assertThat(count("scenario_results")).isEqualTo(3);
    }
    @Test void latestProcessingOrFailedRevisionDoesNotFallBackToOldDone() throws Exception {
        long owner = user(); var first = upload(owner); results.accept(success(first, clean(first.getId())));
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5, default_review_cycle_days=7, default_order_unit_qty=1 WHERE id=?", first.getWarehouseId());
        var second = upload(owner);
        assertThat(calculations.request(owner, first.getWarehouseId(), "SETTINGS", false)).isNull();
        results.accept(failure(second));
        assertThat(calculations.request(owner, first.getWarehouseId(), "SETTINGS", false)).isNull();
    }

    @Test void calculationSnapshotIsOwnerScopedAndImmutable() throws Exception {
        long owner = user(), other = user(); var u = upload(owner);
        results.accept(success(u, clean(u.getId())));
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5, default_review_cycle_days=7, default_order_unit_qty=1 WHERE id=?", u.getWarehouseId());
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name,unit_cost,is_active) VALUES (?,?,?,NULL,TRUE)", owner, "NO-COST", "No cost");
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name,unit_cost,is_active) VALUES (?,?,?,100,FALSE)", owner, "INACTIVE", "Inactive");
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name,unit_cost,is_active) VALUES (?,?,?,100,TRUE)", other, "OTHER", "Other");
        long product = jdbc.queryForObject("SELECT id FROM products WHERE user_id=? AND sku_barcode='SKU-A'", Long.class, owner);
        long missing = jdbc.queryForObject("SELECT id FROM products WHERE user_id=? AND sku_barcode='NO-COST'", Long.class, owner);
        jdbc.update("INSERT INTO planned_receipts(product_id,warehouse_id,expected_on,quantity,status,created_at,updated_at) VALUES (?,?,NULL,5,'NO_DATE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", product,u.getWarehouseId());
        jdbc.update("INSERT INTO planned_receipts(product_id,warehouse_id,expected_on,quantity,status,created_at,updated_at) VALUES (?,?,?,6,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", product,u.getWarehouseId(),DAY.minusDays(1));
        jdbc.update("INSERT INTO planned_receipts(product_id,warehouse_id,expected_on,quantity,status,created_at,updated_at) VALUES (?,?,?,7,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", product,u.getWarehouseId(),LocalDate.now().plusDays(30));
        long run = calculations.request(owner, u.getWarehouseId(), "SETTINGS", false);
        String id = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id=?", String.class, run);
        var input = jobs.find(id).payload();
        assertThat(input.path("productIds").size()).isEqualTo(2);
        assertThat(input.path("productIds").toString()).contains(Long.toString(product), Long.toString(missing));
        assertThat(input.path("settings").size()).isEqualTo(1);
        assertThat(input.path("plannedReceipts").size()).isEqualTo(1);
        assertThat(input.path("plannedReceipts").get(0).path("quantity").asLong()).isEqualTo(7);
        // NO_DATE stays out of plannedReceipts and is sent separately as explanation-only metadata (#86).
        assertThat(input.path("noDateReceipts").size()).isEqualTo(1);
        assertThat(input.path("noDateReceipts").get(0).path("productId").asLong()).isEqualTo(product);
        assertThat(input.path("noDateReceipts").get(0).path("quantity").asLong()).isEqualTo(5);
        assertThat(input.path("noDateReceipts").get(0).has("expectedOn")).isFalse();
        jdbc.update("UPDATE products SET unit_cost=999 WHERE id=?", product);
        assertThat(jobs.find(id).payload().path("settings").get(0).path("unitCost").asLong()).isEqualTo(100);
        assertThat(calculations.request(owner, u.getWarehouseId(), "SETTINGS", false)).isNotEqualTo(run);
    }

    @Test void unchangedInputReusesAutomaticRun() throws Exception {
        long owner = user(); var u = upload(owner);
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5, default_review_cycle_days=7, default_order_unit_qty=1 WHERE id=?", u.getWarehouseId());
        results.accept(success(u, clean(u.getId())));
        long initial = jdbc.queryForObject("SELECT id FROM calculation_runs", Long.class);
        Long repeated = calculations.request(owner, u.getWarehouseId(), "SETTINGS", false);
        String firstJob = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id=?", String.class, initial);
        String nextJob = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id=?", String.class, repeated);
        var firstCost = jobs.find(firstJob).payload().path("settings").get(0).path("unitCost").decimalValue();
        var nextCost = jobs.find(nextJob).payload().path("settings").get(0).path("unitCost").decimalValue();
        assertThat(firstCost).isEqualByComparingTo(nextCost);
        assertThat(repeated).as("equivalent unit cost decimal scales %s/%s", firstCost.scale(), nextCost.scale()).isEqualTo(initial);
        // A new NO_DATE receipt changes the insight input, so the run is not reused.
        long product = jdbc.queryForObject("SELECT id FROM products WHERE user_id=? AND sku_barcode='SKU-A'", Long.class, owner);
        jdbc.update("INSERT INTO planned_receipts(product_id,warehouse_id,expected_on,quantity,status,created_at,updated_at) VALUES (?,?,NULL,3,'NO_DATE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", product, u.getWarehouseId());
        assertThat(calculations.request(owner, u.getWarehouseId(), "RECEIPTS", false)).isNotEqualTo(initial);
    }

    @Test void restoringEarlierInputCreatesNewRunInsteadOfReusingHistory() throws Exception {
        long owner = user(); var u = upload(owner);
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5, default_review_cycle_days=7, default_order_unit_qty=1 WHERE id=?", u.getWarehouseId());
        results.accept(success(u, clean(u.getId())));
        long initial = jdbc.queryForObject("SELECT id FROM calculation_runs", Long.class);
        var cost = jdbc.queryForObject("SELECT unit_cost FROM products WHERE user_id=?", java.math.BigDecimal.class, owner);
        jdbc.update("UPDATE products SET unit_cost=? WHERE user_id=?", cost.add(java.math.BigDecimal.ONE), owner);
        long changed = calculations.request(owner, u.getWarehouseId(), "SETTINGS", false);
        jdbc.update("UPDATE products SET unit_cost=? WHERE user_id=?", cost, owner);
        long restored = calculations.request(owner, u.getWarehouseId(), "SETTINGS", false);
        assertThat(changed).isGreaterThan(initial);
        assertThat(restored).as("restoring input must become the newest visible calculation").isGreaterThan(changed);
        assertThat(calculations.request(owner, u.getWarehouseId(), "SETTINGS", false)).isEqualTo(restored);
    }

    @Test void failedLatestRunDoesNotReuseAnOlderSuccessfulRun() throws Exception {
        long owner = user(); var u = upload(owner);
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5, default_review_cycle_days=7, default_order_unit_qty=1 WHERE id=?", u.getWarehouseId());
        results.accept(success(u, clean(u.getId())));
        long initial = jdbc.queryForObject("SELECT id FROM calculation_runs", Long.class);
        jdbc.update("UPDATE calculation_runs SET status='DONE' WHERE id=?", initial);
        long failed = calculations.request(owner, u.getWarehouseId(), "RECALCULATE", true);
        jdbc.update("UPDATE calculation_runs SET status='FAILED', error_message='isolated failure fixture' WHERE id=?", failed);
        long retry = calculations.request(owner, u.getWarehouseId(), "SETTINGS", false);
        assertThat(retry).as("a failed latest attempt requires a new run rather than an old DONE ID").isGreaterThan(failed);
    }

    @Test void cleanAndCalculationEnqueueRollbackTogetherOnOutboxFailure() throws Exception {
        long owner = user(); var u = upload(owner);
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5, default_review_cycle_days=7, default_order_unit_qty=1 WHERE id=?", u.getWarehouseId());
        faultDdl("ALTER TABLE engine_jobs ADD CONSTRAINT test_deny_calculate CHECK (job_type <> 'CALCULATE')");
        try {
            assertThatThrownBy(() -> results.accept(success(u, clean(u.getId())))).isInstanceOf(RuntimeException.class);
            assertThat(count("products")).isZero();
            assertThat(count("daily_stock_records")).isZero();
            assertThat(count("calculation_runs")).isZero();
            assertThat(jobs.find(u.getJobId()).status()).isEqualTo("PENDING");
            assertThat(uploads.findById(u.getId()).orElseThrow().getStatus()).isEqualTo("PROCESSING");
        } finally {
            faultDdl("ALTER TABLE engine_jobs DROP CONSTRAINT test_deny_calculate");
        }
    }

}
