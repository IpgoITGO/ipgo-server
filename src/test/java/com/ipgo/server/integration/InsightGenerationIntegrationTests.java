package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleanedStockUnit;
import com.ipgo.server.infrastructure.InsightEngineClient;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.service.*;
import com.ipgo.server.support.UploadTestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic calculation -> real local HTTP -> persisted K4 API. Never contacts a paid provider. */
@AutoConfigureMockMvc
class InsightGenerationIntegrationTests extends DatabaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired TransactionTemplate tx;
    @Autowired InsightRenderer renderer;
    @Autowired InsightService insights;
    @Autowired MockMvc mvc;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRegistrationService registration;
    @Autowired ProductRepository products;
    HttpServer http;
    InsightEngineClient client;
    InsightGeneration generation;
    AtomicInteger calls;
    volatile JsonNode sent;
    volatile String reply;
    volatile int httpStatus;
    volatile long delay;
    long owner, warehouse, product, run;
    ObjectNode facts;

    @BeforeEach void setup() throws Exception {
        UploadTestDatabase.clear(jdbc);
        calls = new AtomicInteger(); httpStatus = 200; delay = 0;
        facts = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/insight/result.json"));
        reply = mapper.writeValueAsString(Map.of("validation", "PASSED", "draft", "{{qty_p100}}개를 넣으면 {{ending_p100}}개가 남습니다. 위험은 {{risk_basis}} 추정치로 {{risk_post_p100}}입니다.", "promptVersion", "test-1", "latencyMs", 3, "costUsd", 0));
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/engine/insight", e -> {
            calls.incrementAndGet(); sent = mapper.readTree(e.getRequestBody());
            try { Thread.sleep(delay); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            try { e.sendResponseHeaders(httpStatus, bytes.length); e.getResponseBody().write(bytes); } finally { e.close(); }
        });
        http.start();
        client = new InsightEngineClient("http://127.0.0.1:" + http.getAddress().getPort(), Duration.ofMillis(500), mapper);
        generation = service(true);
        owner = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        warehouse = warehouses.lockDefault(owner).getId();
        registration.register(owner, warehouse, List.of(new CleanedStockUnit("test-"+owner, "비공개 상품", "lot-"+owner, LocalDate.of(2026, 9, 1))));
        product = products.findByUserIdAndSkuBarcodeIn(owner, List.of("test-"+owner)).getFirst().getId();
        facts.put("productId", product);
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',?,'QUEUED','test',JSON '{}',CURRENT_TIMESTAMP)", warehouse, LocalDate.of(2026,9,1));
        run = jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs", Long.class);
    }
    @AfterEach void cleanup() { client.close(); http.stop(0); UploadTestDatabase.clear(jdbc); }
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

    @Test void calculatesCallsFillsStoresAndServesK4WithoutDisclosingFacts() throws Exception {
        long id = save();
        assertThat(state()).isEqualTo("PENDING");
        mvc.perform(get("/api/results/"+id+"/insight").with(jwt().jwt(c -> c.subject(""+owner))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("status").value("GENERATING"));
        generation.tick(); generation.tick();
        assertThat(calls).hasValue(1);
        assertThat(sent.path("resultId").asLong()).isEqualTo(id);
        assertThat(sent.path("input").path("skuLabel").asText()).isEqualTo("이 상품");
        assertThat(sent.toString()).doesNotContain("unitCost", "비공개", "forecastOutboundQty", "userId", "원래 상품명");
        assertThat(state()).isEqualTo("DONE");
        mvc.perform(get("/api/results/"+id+"/insight").with(jwt().jwt(c -> c.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("mode").value("AI"))
                .andExpect(jsonPath("isFallback").value(false))
                .andExpect(jsonPath("text").value("100개를 넣으면 40개가 남습니다. 위험은 전체 상품의 과거 오차 기준 추정치로 6%입니다."));
        mvc.perform(get("/api/results/"+id+"/insight").with(jwt().jwt(c -> c.subject(""+(owner+99999)))))
                .andExpect(status().isForbidden());
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="IPGO_INSIGHT_LOCAL_FASTAPI", matches="true")
    void realFastApiValidatorAndFakeLlmReachPersistedK4() {
        // Fixed disposable-network endpoint; cannot select a production or paid provider URL.
        try (LocalContractClient ignored = new LocalContractClient()) {
            generation = new InsightGeneration(jdbc, mapper, renderer, ignored.client, tx, true, Duration.ofSeconds(2));
            long id=save(); generation.tick();
            var result=insights.insight(owner,id).orElseThrow();
            assertThat(result.mode()).isEqualTo("AI");
            assertThat(result.text()).contains("100개", "40개", "10%", "6%");
            assertThat(result.isFallback()).isFalse();
            assertThat(calls).hasValue(0);
        }
    }
    private class LocalContractClient implements AutoCloseable {
        final InsightEngineClient client=new InsightEngineClient("http://ipgo-insight-fake-api:8000",Duration.ofSeconds(2),mapper);
        public void close() { client.close(); }
    }

    @Test void usesStoredPrecisionForTextAndComparisonDirections() {
        ((ObjectNode)facts.path("scenarios").get(1)).put("expectedEndingStock",40.9999).put("postArrivalRisk",0.06499);
        long id=save(); generation.tick();
        assertThat(insights.insight(owner,id).orElseThrow().text()).contains("41개", "7%");
        assertThat(sent.path("input").path("directions").path("risk_post_p80_vs_p100").asText()).isEqualTo("낮음");
    }

    @Test void disabledNeverCallsOrChangesHistoricalDisabledRows() {
        generation = service(false); long id = save(); generation.tick();
        assertThat(state()).isEqualTo("DISABLED"); assertThat(reason()).isEqualTo("LLM_DISABLED");
        generation = service(true); generation.tick();
        assertThat(calls).hasValue(0); assertThat(insights.insight(owner,id).orElseThrow().mode()).isEqualTo("TABLE_ONLY");
    }
    @Test void missingOrInconsistentInputSkipsWithoutLlm() {
        ((ObjectNode)facts.path("insightInput")).put("riskStatus", "LOW_GRADE");
        save(); generation.tick();
        assertThat(state()).isEqualTo("SKIPPED"); assertThat(reason()).isEqualTo("INPUT_UNAVAILABLE"); assertThat(calls).hasValue(0);
    }
    @Test void nullInputSkipsWithoutFabricatingRisk() {
        facts.putNull("insightInput"); facts.putNull("riskStatus"); save(); generation.tick();
        assertThat(reason()).isEqualTo("INPUT_UNAVAILABLE"); assertThat(calls).hasValue(0);
    }
    @Test void failedUpstreamUsesCalculatedTemplateAndRetainsCalculation() {
        reply = "{\"validation\":\"FAILED\",\"draft\":null,\"failReason\":\"LLM_ERROR\",\"promptVersion\":\"test-1\",\"latencyMs\":3,\"costUsd\":0}";
        long id = save(); generation.tick(); generation.tick();
        var result = insights.insight(owner,id).orElseThrow();
        assertThat(result.mode()).isEqualTo("FIXED"); assertThat(result.isFallback()).isTrue();
        assertThat(result.text()).contains("100개", "6%", "같습니다", "여유 배수 105%");
        assertThat(reason()).isEqualTo("LLM_ERROR"); assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT status FROM calculation_runs WHERE id=?",String.class,run)).isEqualTo("DONE");
    }
    @Test void rawNumbersAreRejectedEvenWhenUpstreamClaimsPassed() {
        reply=reply.replace("{{qty_p100}}", "999999"); long id=save(); generation.tick();
        assertThat(reason()).isEqualTo("VALUE_MISMATCH");
        assertThat(insights.insight(owner,id).orElseThrow().text()).doesNotContain("999999");
    }
    @Test void unknownPlaceholdersAreRejected() {
        reply=reply.replace("{{qty_p100}}", "{{unit_cost}}"); save(); generation.tick();
        assertThat(reason()).isEqualTo("VALUE_MISMATCH");
    }
    @Test void malformedOrMockResponseNeverBecomesAi() {
        reply=reply.replace("test-1", "mock"); long id=save(); generation.tick();
        assertThat(reason()).isEqualTo("VALIDATION_FAILED"); assertThat(insights.insight(owner,id).orElseThrow().mode()).isEqualTo("FIXED");
    }
    @Test void negativeMetricsAreRejectedBeforeDatabaseWrite() {
        reply=reply.replace("\"latencyMs\":3", "\"latencyMs\":-1"); save(); generation.tick();
        assertThat(state()).isEqualTo("DONE"); assertThat(reason()).isEqualTo("VALIDATION_FAILED");
    }
    @Test void upstreamNotConfiguredBecomesFixed() {
        httpStatus=501; save(); generation.tick();
        assertThat(reason()).isEqualTo("LLM_NOT_CONFIGURED"); assertThat(state()).isEqualTo("DONE");
    }
    @Test void upstreamInputErrorBecomesFixed() {
        httpStatus=422; save(); generation.tick(); assertThat(reason()).isEqualTo("INVALID_INPUT");
    }
    @Test void timeoutIsTerminalAndNotRetried() {
        delay=800; save(); generation.tick(); generation.tick();
        assertThat(reason()).isEqualTo("LLM_TIMEOUT"); assertThat(calls).hasValue(1);
    }
    @Test void oversizedResponseIsBounded() {
        reply="x".repeat(70000); save(); generation.tick(); assertThat(state()).isEqualTo("DONE");
        assertThat(reason()).isEqualTo("LLM_ERROR");
    }
    @Test void crashedClaimExpiresWithoutRepeatingUnknownPaidCall() {
        long id=save(); jdbc.update("UPDATE ai_explanations SET status='PROCESSING',created_at=? WHERE result_id=?", Timestamp.from(Instant.now().minusSeconds(120)),id);
        generation.tick(); assertThat(calls).hasValue(0); assertThat(reason()).isEqualTo("LLM_TIMEOUT");
        assertThat(insights.insight(owner,id).orElseThrow().mode()).isEqualTo("FIXED");
    }
    @Test void competingWorkersClaimOnlyOnce() throws Exception {
        save();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(generation::tick); var b=pool.submit(generation::tick); a.get(); b.get();
        }
        assertThat(calls).hasValue(1); assertThat(state()).isEqualTo("DONE");
    }
    @Test void rolledBackCalculationLeavesNoExplanationOrCall() {
        ((ObjectNode) facts.path("scenarios").get(2)).put("type", "INVALID");
        assertThatThrownBy(this::save).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_explanations",Integer.class)).isZero();
        generation.tick(); assertThat(calls).hasValue(0);
    }
    @Test void nonOkCalculationDoesNotEnqueueExplanation() {
        facts.put("status", "NOT_NEEDED"); facts.put("recommendedQty",0); facts.putArray("scenarios");
        long id=save(); generation.tick(); assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_explanations",Integer.class)).isZero();
        assertThat(insights.insight(owner,id).orElseThrow().mode()).isEqualTo("FIXED");
    }
}
