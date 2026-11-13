package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleanedStockUnit;
import com.ipgo.server.infrastructure.InsightEngineClient;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.service.*;
import com.ipgo.server.support.UploadTestDatabase;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Synthetic calculation -> H2 persistence -> in-process provider stub. No sockets or paid provider. */
class InsightLowGradeOfflineIntegrationTests extends DatabaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired TransactionTemplate tx;
    @Autowired InsightRenderer renderer;
    @Autowired InsightService insights;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRegistrationService registration;
    @Autowired ProductRepository products;
    InsightEngineClient client;
    InsightGeneration generation;
    AtomicInteger calls;
    volatile JsonNode sent;
    volatile String reply;
    long owner, warehouse, product, run;
    ObjectNode facts;

    @BeforeEach void setup() throws Exception {
        UploadTestDatabase.clear(jdbc);
        calls = new AtomicInteger();
        facts = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/insight/result.json"));
        reply = mapper.writeValueAsString(Map.of("validation", "PASSED", "draft", "{{qty_p100}}개를 넣으면 {{ending_p100}}개가 남습니다. 위험은 {{risk_basis}} 추정치로 {{risk_post_p100}}입니다.", "promptVersion", "test-1", "latencyMs", 3, "costUsd", 0));
        client = new InsightEngineClient("", Duration.ofMillis(500), mapper) {
            @Override public JsonNode generate(long resultId, JsonNode input) {
                calls.incrementAndGet();
                sent=mapper.valueToTree(Map.of("resultId",resultId,"input",input));
                return mapper.readTree(reply);
            }
        };
        generation = service(true);
        owner = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        warehouse = warehouses.lockDefault(owner).getId();
        registration.register(owner, warehouse, List.of(new CleanedStockUnit("test-"+owner, "비공개 상품", "lot-"+owner, LocalDate.of(2026, 9, 1))));
        product = products.findByUserIdAndSkuBarcodeIn(owner, List.of("test-"+owner)).getFirst().getId();
        facts.put("productId", product);
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',?,'QUEUED','test',JSON '{}',CURRENT_TIMESTAMP)", warehouse, LocalDate.of(2026,9,1));
        run = jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs", Long.class);
    }
    @AfterEach void cleanup() { client.close(); UploadTestDatabase.clear(jdbc); }
    InsightGeneration service(boolean enabled) { return new InsightGeneration(jdbc, mapper, renderer, client, tx, enabled, Duration.ofMillis(500)); }
    long save() {
        JsonNode input = mapper.readTree(mapper.writeValueAsString(Map.of("userId", owner, "productIds", List.of(product), "asOfDate", "2026-09-01", "today", "2026-09-01", "settings", List.of(), "plannedReceipts", List.of())));
        String jobId = "insight-" + run;
        jdbc.update("UPDATE calculation_runs SET job_id=?, selection_snapshot=CAST(? AS JSONB) WHERE id=?", jobId,
                mapper.writeValueAsString(Map.of("request", input, "productIds", List.of(product))), run);
        var job = new EngineJobs.Job(jobId, "CALCULATE", run, warehouse, input, "PENDING");
        var writer = new EngineResultWriter(jdbc, mapper, generation);
        JsonNode response = mapper.valueToTree(Map.of("engineVersion", "test", "results", List.of(facts)));
        tx.executeWithoutResult(s -> writer.complete(job, response));
        // Redelivery must not enqueue another explanation.
        tx.executeWithoutResult(s -> writer.complete(job, response));
        return jdbc.queryForObject("SELECT id FROM calculation_results WHERE run_id=?", Long.class, run);
    }
    String state() { return jdbc.queryForObject("SELECT status FROM ai_explanations", String.class); }
    String reason() { return jdbc.queryForObject("SELECT reason_code FROM ai_explanations", String.class); }

    void shortHistory() {
        facts.put("riskStatus","LOW_GRADE").put("accuracyGrade","LOW").put("validObservationDays",12);
        facts.putNull("forecastRangeLow").putNull("forecastRangeHigh").putNull("preArrivalRisk").putNull("riskBasis");
        for(var s:facts.path("scenarios")) ((ObjectNode)s).putNull("postArrivalRisk");
        var in=(ObjectNode)facts.path("insightInput");
        in.put("riskStatus","LOW_GRADE");
        var keys=in.putArray("placeholders");
        for(String k:List.of("method","qty_p80","qty_p100","qty_p120","ending_p80","ending_p100","ending_p120")) keys.add(k);
        in.putObject("directions").put("ending_p80_vs_p100","낮음").put("ending_p120_vs_p100","높음");
    }
    @Test void lowGradeNullRangePersistsAndSendsOnlyAvailableFactsOnce() {
        shortHistory();
        reply=mapper.writeValueAsString(Map.of("validation","PASSED","draft","{{qty_p100}}개를 넣으면 {{ending_p100}}개가 남습니다. 예측 기록이 짧아 품절 위험과 예상 출고량 범위는 산출하지 않았습니다.","promptVersion","offline-stub","latencyMs",1,"costUsd",0));
        long id=save(); assertThat(state()).isEqualTo("PENDING");
        generation.tick(); generation.tick();
        assertThat(calls).hasValue(1); assertThat(state()).isEqualTo("DONE");
        assertThat(sent.path("input").path("riskStatus").asText()).isEqualTo("LOW_GRADE");
        assertThat(sent.path("input").toString()).doesNotContain("forecast_range", "risk_pre", "risk_post", "unitCost");
        var result=insights.insight(owner,id).orElseThrow();
        assertThat(result.mode()).isEqualTo("AI");
        assertThat(result.text()).contains("산출하지 않았습니다").doesNotContain("{{", "%", "~");
        assertThat(jdbc.queryForMap("SELECT forecast_range_low,forecast_range_high FROM calculation_results WHERE id=?",id).values()).containsOnlyNulls();
    }
    @Test void lowGradeNullRangeRejectedDraftUsesSafeFallbackWithoutRetry() {
        shortHistory();
        reply=mapper.writeValueAsString(Map.of("validation","PASSED","draft","{{qty_p100}}개이며 범위는 {{forecast_range_low}}개입니다.","promptVersion","offline-stub","latencyMs",1,"costUsd",0));
        long id=save(); generation.tick(); generation.tick();
        assertThat(calls).hasValue(1);
        var result=insights.insight(owner,id).orElseThrow();
        assertThat(result.mode()).isEqualTo("FIXED");
        assertThat(result.text()).contains("범위도 산출하지 않았습니다", "여유 배수 105%")
                .doesNotContain("{{", "~", "위험은 10%", "위험은 0%");
    }
    @Test void malformedRangeDoesNotEnqueueOrCallProvider() {
        shortHistory(); facts.put("forecastRangeHigh",20);
        save(); generation.tick();
        assertThat(state()).isEqualTo("SKIPPED"); assertThat(reason()).isEqualTo("INPUT_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }
}
