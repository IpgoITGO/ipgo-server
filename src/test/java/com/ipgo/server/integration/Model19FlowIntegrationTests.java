package com.ipgo.server.integration;

import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.service.CalculationRunService;
import com.ipgo.server.service.EngineCalculationService;
import com.ipgo.server.service.EngineJobs;
import com.ipgo.server.service.EngineResults;
import com.ipgo.server.service.UploadRecordService;
import com.ipgo.server.support.UploadTestDatabase;
import com.ipgo.server.support.UploadTestFiles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: an isolated PostgreSQL + a real FastAPI and private model; never an AWS endpoint. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="IPGO_MODEL19_API_URL", matches="http://127\\.0\\.0\\.1:[0-9]+")
class Model19FlowIntegrationTests {
    static final Path PACKET = Path.of(".model19-validation");
    static final LocalDate DAY = LocalDate.of(2026, 9, 10);
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired UploadRecordService records;
    @Autowired UploadFileRepository uploads;
    @Autowired EngineCalculationService calculations;
    @Autowired EngineResults results;
    @Autowired EngineJobs jobs;
    @Autowired CalculationRunService status;

    static JsonNode identity() {
        try { return JsonMapper.builder().build().readTree(Files.readString(PACKET.resolve("identity.json"))); }
        catch (Exception e) { throw new IllegalStateException("Private local validation identity is required", e); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        int port = Integer.parseInt(System.getenv("IPGO_MODEL19_PG_PORT"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid local test port");
        r.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"+port+"/engine_test");
        r.add("spring.datasource.username", () -> "engine_test");
        r.add("spring.datasource.password", () -> "");
        r.add("ipgo.auth.jwt-secret", () -> "c3ludGhldGljLXRlc3Qta2V5LTMyaXRlbXMtb25seSE=");
        r.add("ipgo.engine.enabled", () -> "true");
        r.add("ipgo.engine.transport-enabled", () -> "false");
        r.add("ipgo.engine.default-method", () -> "LGBM19");
        r.add("ipgo.engine.model-version", () -> identity().path("modelVersion").asText());
        r.add("ipgo.upload.directory", () -> PACKET.resolve("uploads").toAbsolutePath().toString());
    }

    JsonNode post(String path, String body) throws Exception {
        String base = System.getenv("IPGO_MODEL19_API_URL");
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("local FastAPI status").isEqualTo(200);
        return mapper.readTree(response.body());
    }

    @Test void realModelReadsSameDatabaseAndServerStoresItsResultsIdempotently() throws Exception {
        long owner = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        String barcode = identity().path("barcode").asText();
        var file = new MockMultipartFile("file", "2026-09.xlsx", "application/octet-stream", UploadTestFiles.workbook());
        long uploadId = records.upload(owner, file, true).uploadId();
        UploadFile upload = uploads.findById(uploadId).orElseThrow();
        LocalDate first = DAY.withDayOfMonth(1);
        var cleaned = new CleaningResult(first, DAY, DAY,
                List.of(new CleaningResult.CleanProduct(barcode, "Synthetic model validation", 100L, true)),
                List.of(new CleaningResult.Unit("LOCAL-MODEL-LOT", barcode, first)),
                java.util.stream.IntStream.rangeClosed(1,10).mapToObj(i -> new CleaningResult.UnitDay(uploadId,
                        "LOCAL-MODEL-LOT", first, first.plusDays(i-1), 0L, 10L, 10L, 0L, 0L, "재고변동", 5)).toList(),
                java.util.stream.IntStream.rangeClosed(1,10).mapToObj(i -> new CleaningResult.ProductDay(uploadId,
                        barcode, first.plusDays(i-1), 0L, 10L, 10L, 0L, 0L, true, null)).toList(),
                new CleaningResult.Validation(1, 1, List.of()), 14);
        results.accept(mapper.writeValueAsString(Map.of("contractVersion","1","jobId",upload.getJobId(),
                "jobType","CLEAN","status","SUCCEEDED","result",cleaned)));
        jdbc.update("UPDATE warehouses SET default_lead_time_days=5,default_review_cycle_days=14,default_order_unit_qty=1 WHERE id=?", upload.getWarehouseId());
        Long run = calculations.request(owner, upload.getWarehouseId(), "SETTINGS", false, DAY);
        assertThat(run).isNotNull();
        String jobId = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id=?",String.class,run);
        var job = jobs.find(jobId);
        assertThat(job.payload().path("policy").path("defaultMethod").asText()).isEqualTo("LGBM19");
        long product = job.payload().path("productIds").get(0).asLong();
        assertThat(job.payload().path("productCodes").path(""+product).asText()).isEqualTo(barcode);
        String request = mapper.writeValueAsString(job.payload());
        JsonNode response = post("/engine/calculate", request);
        JsonNode one = response.path("results").get(0);
        assertThat(one.path("method").asText()).isEqualTo("LGBM19");
        assertThat(one.path("modelVersion").asText()).isEqualTo(identity().path("modelVersion").asText());
        assertThat(one.path("riskStatus").isNull()).isTrue();
        assertThat(one.path("accuracyEvaluation").path("status").asText()).isEqualTo("UNAVAILABLE");
        String envelope = mapper.writeValueAsString(Map.of("contractVersion","1","jobId",jobId,
                "jobType","CALCULATE","status","SUCCEEDED","result",response));
        JsonNode invalid = response.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) invalid.path("results").get(0)).put("modelVersion", "wrong-version");
        assertThatThrownBy(() -> results.accept(mapper.writeValueAsString(Map.of("contractVersion","1","jobId",jobId,
                "jobType","CALCULATE","status","SUCCEEDED","result",invalid))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_results WHERE run_id=?",Long.class,run)).isZero();
        results.accept(envelope);
        results.accept(envelope);
        assertThat(status.status(owner, run).status()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_results WHERE run_id=?",Long.class,run)).isEqualTo(1L);
        long resultId = jdbc.queryForObject("SELECT id FROM calculation_results WHERE run_id=?",Long.class,run);
        assertThat(jdbc.queryForObject("SELECT method FROM calculation_results WHERE id=?",String.class,resultId)).isEqualTo("LGBM19");
        assertThat(jdbc.queryForObject("SELECT recommended_qty FROM calculation_results WHERE id=?",java.math.BigDecimal.class,resultId))
                .isEqualByComparingTo(one.path("recommendedQty").decimalValue());
        String snapshot = jdbc.queryForObject("SELECT selection_snapshot FROM calculation_runs WHERE id=?",String.class,run);
        assertThat(mapper.readTree(snapshot).path("resultFacts").path(""+product).path("modelVersion").asText())
                .isEqualTo(identity().path("modelVersion").asText());
        JsonNode custom = post("/engine/scenarios",mapper.writeValueAsString(Map.of(
                "resultId",resultId,"calculationInput",job.payload(),"qty",5)));
        assertThat(custom.path("qty").asInt()).isEqualTo(5);
        assertThat(custom.path("dailyProjection").size()).isEqualTo(19);
        Files.writeString(PACKET.resolve("receipt.json"),mapper.writeValueAsString(Map.of(
                "modelVersion",identity().path("modelVersion").asText(),"database","PostgreSQL",
                "apiStatus",200,"storedResults",1,"idempotent",true,"customScenarioDays",19,
                "riskUnavailable",true,"runStatus","DONE")));
    }
}
