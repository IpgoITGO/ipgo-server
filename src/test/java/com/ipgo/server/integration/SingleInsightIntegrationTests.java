package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleanedStockUnit;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.infrastructure.InsightEngineClient;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.service.*;
import com.ipgo.server.support.UploadTestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic current result 811 + original 698 budget, local HTTP only. No AWS, OpenAI keys or paid providers. */
@SpringBootTest(properties={"ipgo.insight.single-result-enabled=true","ipgo.insight.enabled=false","ipgo.insight.worker-enabled=false"})
@AutoConfigureMockMvc
class SingleInsightIntegrationTests extends DatabaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired TransactionTemplate tx;
    @Autowired StoredInsightFacts facts;
    @Autowired InsightGeneration generation;
    @Autowired ProductDetailService details;
    @Autowired InsightRegeneration single;
    @Autowired InsightService insights;
    @Autowired MockMvc mvc;
    @Autowired WarehouseService warehouses;
    @Autowired ProductRegistrationService registration;
    @Autowired ProductRepository products;
    @MockitoBean InsightEngineClient engine;
    HttpServer http;
    InsightEngineClient local;
    AtomicInteger calls;
    volatile JsonNode sent;
    volatile String reply;
    volatile long delay;
    volatile int httpStatus;
    long owner,warehouse,product,run;

    @BeforeEach void setup() throws Exception {
        UploadTestDatabase.clear(jdbc);
        calls=new AtomicInteger(); delay=0; httpStatus=200;
        reply=mapper.writeValueAsString(Map.of("validation","PASSED","draft","{{qty_p100}}개 입고 시 {{ending_p100}}개가 남습니다.",
                "promptVersion","single-test-v1","latencyMs",1,"costUsd",0));
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/engine/insight",e -> {
            calls.incrementAndGet(); sent=mapper.readTree(e.getRequestBody());
            try { Thread.sleep(delay); } catch(InterruptedException x) { Thread.currentThread().interrupt(); }
            byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);
            try { e.sendResponseHeaders(httpStatus,bytes.length); e.getResponseBody().write(bytes); } finally { e.close(); }
        });
        http.start();
        local=new InsightEngineClient("http://127.0.0.1:"+http.getAddress().getPort(),Duration.ofMillis(500),mapper);
        doAnswer(a -> local.generate(a.getArgument(0),a.getArgument(1))).when(engine).generate(anyLong(),any());
        owner=UploadTestDatabase.newUser(jdbc,UUID.randomUUID()+"@example.test");
        warehouse=warehouses.lockDefault(owner).getId();
        String barcode="private-"+owner;
        registration.register(owner,warehouse,List.of(new CleanedStockUnit(barcode,"비공개 상품명","lot-"+owner,LocalDate.of(2026,9,1))));
        product=products.findByUserIdAndSkuBarcodeIn(owner,List.of(barcode)).getFirst().getId();
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',?,'DONE','test',JSON '{}',CURRENT_TIMESTAMP)",warehouse,LocalDate.of(2026,9,1));
        long anchorRun=jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs",Long.class);
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',?,'DONE','test',JSON '{}',CURRENT_TIMESTAMP)",warehouse,LocalDate.of(2026,9,1));
        run=jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs",Long.class);
        var input=mapper.valueToTree(Map.of("userId",owner,"warehouseId",warehouse,"productIds",List.of(product),
                "asOfDate","2026-09-01","today","2026-09-01","policy",Map.of("defaultMethod","B","multiplier",new BigDecimal("1.05")),"noDateReceipts",List.of()));
        jdbc.update("""
                INSERT INTO calculation_results(id,run_id,product_id,origin_date,start_date,method,multiplier,horizon_days,
                start_stock_qty,planned_receipt_qty,forecast_outbound_qty,recommended_qty,status,input_snapshot,
                pre_arrival_risk,risk_sample_size,risk_basis,valid_observation_days)
                VALUES(811,?,?,?,?,'B',1.05,14,10.9,5,95.1,100,'OK',CAST(? AS JSONB),0.1,40,'POOLED',40)
                """,run,product,LocalDate.of(2026,9,1),LocalDate.of(2026,9,1),mapper.writeValueAsString(input));
        var original=mapper.readTree(getClass().getResourceAsStream("/insight/result.json"));
        for(JsonNode s:original.path("scenarios")) jdbc.update("""
                INSERT INTO scenario_results(result_id,scenario_type,scenario_qty,is_duplicate,expected_ending_stock,
                unmet_demand_qty,post_arrival_risk,daily_projection) VALUES(811,?,?,?,?,?,?,JSON '[]')
                """,s.path("type").asText(),s.path("qty").asLong(),false,s.path("expectedEndingStock").decimalValue(),
                s.path("unmetDemandQty").decimalValue(),s.path("postArrivalRisk").decimalValue());
        // Historical rows used validation_passed=true even though LLM was disabled.
        jdbc.update("INSERT INTO ai_explanations(result_id,mode,status,request_payload,validation_passed,reason_code,versions) VALUES(811,'NONE','DISABLED',JSON '{}',TRUE,'LLM_DISABLED',JSON '{}')");
        jdbc.update("""
                INSERT INTO calculation_results(id,run_id,product_id,origin_date,start_date,method,multiplier,horizon_days,
                start_stock_qty,planned_receipt_qty,forecast_outbound_qty,recommended_qty,status,input_snapshot,
                pre_arrival_risk,risk_sample_size,risk_basis,valid_observation_days)
                SELECT 698,?,product_id,origin_date,start_date,method,multiplier,horizon_days,start_stock_qty,
                planned_receipt_qty,forecast_outbound_qty,recommended_qty,status,input_snapshot,pre_arrival_risk,
                risk_sample_size,risk_basis,valid_observation_days FROM calculation_results WHERE id=811
                """,anchorRun);
        jdbc.update("""
                INSERT INTO ai_explanations(result_id,mode,status,request_payload,validation_passed,reason_code,versions)
                VALUES(698,'NONE','DISABLED',JSON '{}',TRUE,'LLM_DISABLED',JSON '{}')
                """);

    }
    @AfterEach void cleanup() { local.close(); http.stop(0); UploadTestDatabase.clear(jdbc); }
    String state() { return jdbc.queryForObject("SELECT status FROM ai_explanations WHERE result_id=811",String.class); }
    String marker() { return jdbc.queryForObject("SELECT output_path FROM ai_explanations WHERE result_id=811",String.class); }
    String anchorMarker() { var rows=jdbc.query("SELECT output_path FROM ai_explanations WHERE result_id=698",(rs,n)->rs.getString(1)); return rows.isEmpty()?null:rows.getFirst(); }
    void spent() { assertThatThrownBy(() -> single.regenerate(owner,811)).isInstanceOfSatisfying(ProductException.class,e -> assertThat(e.code()).isEqualTo("INSIGHT_ATTEMPT_SPENT")); }
    void unavailable() { assertThatThrownBy(() -> single.regenerate(owner,811)).isInstanceOfSatisfying(ProductException.class,e -> assertThat(e.code()).isEqualTo("INSIGHT_INPUT_UNAVAILABLE")); assertThat(calls).hasValue(0); assertThat(marker()).isNull(); assertThat(anchorMarker()).isNull(); }
    void input(java.util.function.Consumer<ObjectNode> change) {
        JsonNode p=mapper.readTree(jdbc.queryForObject("SELECT input_snapshot FROM calculation_results WHERE id=811",String.class));
        if(p.isString()) p=mapper.readTree(p.asText());
        change.accept((ObjectNode)p);
        jdbc.update("UPDATE calculation_results SET input_snapshot=CAST(? AS JSONB) WHERE id=811",mapper.writeValueAsString(p));
    }

    @Test void postRestoresLegacyInputAndPersistsAiForExistingGet() throws Exception {
        mvc.perform(post("/api/results/811/insight/regenerate").with(jwt().jwt(j -> j.subject(""+owner))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("mode").value("AI"))
                .andExpect(jsonPath("text").value("100개 입고 시 40개가 남습니다."));
        mvc.perform(get("/api/results/811/insight").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("mode").value("AI"));
        assertThat(calls).hasValue(1); assertThat(state()).isEqualTo("DONE"); assertThat(marker()).isEqualTo("single-result/811/attempt-1");
        assertThat(anchorMarker()).isEqualTo("single-budget/698-to-811/attempt-1");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_explanations WHERE result_id=698",String.class)).isEqualTo("DISABLED");
        assertThat(insights.insight(owner,698).orElseThrow().mode()).isEqualTo("TABLE_ONLY");
        assertThat(legacyReserve()).isFalse();
        assertThat(sent.path("resultId").asLong()).isEqualTo(811);
        assertThat(sent.path("input").path("skuLabel").asText()).isEqualTo("이 상품");
        assertThat(sent.toString()).doesNotContain("비공개 상품명","unitCost","warehouseId","userId","startStockQty");
        assertThat(jdbc.queryForObject("SELECT recommended_qty FROM calculation_results WHERE id=811",Long.class)).isEqualTo(100);
        spent(); assertThat(calls).hasValue(1);
    }
    @Test void getDoesNotGenerateAndGlobalTickCannotDrain() throws Exception {
        generation.tick(); single.recover();
        mvc.perform(get("/api/results/811/insight").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(jsonPath("mode").value("TABLE_ONLY"));
        assertThat(calls).hasValue(0); assertThat(state()).isEqualTo("DISABLED");
    }
    @Test void preflightValidatesWithoutReservingOrCalling() throws Exception {
        for(int n=0;n<2;n++) mvc.perform(get("/api/results/811/insight/regeneration").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("remainingAttempts").value(1)).andExpect(jsonPath("productId").value(product));
        assertThat(marker()).isNull(); assertThat(calls).hasValue(0);
        single.regenerate(owner,811);
        mvc.perform(get("/api/results/811/insight/regeneration").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("INSIGHT_ATTEMPT_SPENT"));
        assertThat(calls).hasValue(1);
    }
    @Test void authenticationAndExistingBearerPolicyRemainUnchanged() throws Exception {
        mvc.perform(post("/api/results/811/insight/regenerate").with(csrf())).andExpect(status().isUnauthorized());
        assertThat(calls).hasValue(0);
        mvc.perform(post("/api/results/811/insight/regenerate").with(jwt().jwt(j -> j.subject(""+owner)))).andExpect(status().isOk());
        assertThat(calls).hasValue(1);
    }
    @Test void otherOwnerAndOtherResultsAreRejected() throws Exception {
        mvc.perform(get("/api/results/811/insight/regeneration").with(jwt().jwt(j -> j.subject(""+(owner+10000))))).andExpect(status().isForbidden());
        mvc.perform(get("/api/results/698/insight/regeneration").with(jwt().jwt(j -> j.subject(""+owner)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/results/811/insight/regenerate").with(jwt().jwt(j -> j.subject(""+(owner+10000)))).with(csrf())).andExpect(status().isForbidden());
        for(long id:List.of(698L,810L,812L,1L)) mvc.perform(post("/api/results/"+id+"/insight/regenerate").with(jwt().jwt(j -> j.subject(""+owner))).with(csrf())).andExpect(status().isForbidden());
        assertThat(calls).hasValue(0); assertThat(marker()).isNull();
    }
    @Test void callerCannotSupplyFactsOrResetBudget() throws Exception {
        mvc.perform(post("/api/results/811/insight/regenerate").with(jwt().jwt(j -> j.subject(""+owner))).with(csrf())
                .contentType("application/json").content("{\"resultId\":698,\"retry\":true}"))
                .andExpect(status().isBadRequest());
        assertThat(marker()).isNull(); assertThat(calls).hasValue(0);
    }
    @Test void disabledGateAndUnsafeStartupCannotCall() {
        try(var closed=new InsightRegeneration(jdbc,mapper,facts,generation,details,tx,false,false,false,Duration.ofSeconds(1))) {
            assertThat(closed.preview(owner,811).enabled()).isFalse();
            assertThatThrownBy(() -> closed.regenerate(owner,811)).isInstanceOfSatisfying(ProductException.class,e -> assertThat(e.code()).isEqualTo("INSIGHT_SINGLE_DISABLED"));
        }
        for(boolean[] flags:List.of(new boolean[]{true,false},new boolean[]{false,true}))
            assertThatThrownBy(() -> new InsightRegeneration(jdbc,mapper,facts,generation,details,tx,true,flags[0],flags[1],Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }
    @Test void concurrentRequestsSpendOneDurableAttempt() throws Exception {
        delay=200;
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=pool.invokeAll(List.<java.util.concurrent.Callable<Boolean>>of(() -> attempt(),() -> attempt()));
            assertThat(tasks.stream().map(f -> { try { return f.get(); } catch(Exception e) { throw new RuntimeException(e); } }).filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        assertThat(calls).hasValue(1); assertThat(state()).isEqualTo("DONE");
    }
    // The pre-811 application locked and conditionally claimed only the 698 explanation row.
    boolean legacyReserve() {
        return Boolean.TRUE.equals(tx.execute(s -> {
            jdbc.queryForList("SELECT result_id FROM ai_explanations WHERE result_id=698 FOR UPDATE",Long.class);
            return jdbc.update("""
                    UPDATE ai_explanations SET output_path='single-result/698/attempt-1',status='PROCESSING',created_at=CURRENT_TIMESTAMP
                    WHERE result_id=698 AND output_path IS NULL AND status='DISABLED' AND mode='NONE' AND reason_code='LLM_DISABLED'
                    """)==1;
        }));
    }
    @Test void old698AndNew811ClaimsShareOneGrantUnderConcurrency() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var newer=pool.submit(this::attempt);var older=pool.submit(this::legacyReserve);
            assertThat((newer.get()?1:0)+(older.get()?1:0)).isEqualTo(1);
        }
        assertThat(calls.get()).isLessThanOrEqualTo(1);spent();
    }
    @Test void previouslySpent698Blocks811WithoutRearming() {
        assertThat(legacyReserve()).isTrue();spent();
        assertThat(calls).hasValue(0);assertThat(marker()).isNull();
        assertThat(anchorMarker()).isEqualTo("single-result/698/attempt-1");
    }
    @Test void missingOldBudgetCannotCreateNewAllowance() {
        jdbc.update("DELETE FROM ai_explanations WHERE result_id=698");unavailable();
    }
    @Test void bothBudgetAndTargetClaimsRollbackBeforeAnyHttpOnStorageFailure() {
        jdbc.execute("ALTER TABLE ai_explanations ADD CONSTRAINT reject_target_claim CHECK (result_id<>811 OR output_path IS NULL)");
        try {assertThatThrownBy(() -> single.regenerate(owner,811)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally {jdbc.execute("ALTER TABLE ai_explanations DROP CONSTRAINT reject_target_claim");}
        assertThat(marker()).isNull();assertThat(anchorMarker()).isNull();assertThat(calls).hasValue(0);
        single.regenerate(owner,811);assertThat(calls).hasValue(1);spent();
    }
    @Test void changedCurrentRunStopsBeforeSpendingEitherGrant() {
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',?,'DONE','new',JSON '{}',CURRENT_TIMESTAMP)",warehouse,LocalDate.of(2026,9,1));
        assertThatThrownBy(() -> single.preview(owner,811)).isInstanceOfSatisfying(ProductException.class,e -> assertThat(e.code()).isEqualTo("INSIGHT_RESULT_STALE"));
        assertThatThrownBy(() -> single.regenerate(owner,811)).isInstanceOfSatisfying(ProductException.class,e -> assertThat(e.code()).isEqualTo("INSIGHT_RESULT_STALE"));
        assertThat(calls).hasValue(0);assertThat(marker()).isNull();assertThat(anchorMarker()).isNull();
    }
    @Test void oldUnknownAttemptIsOnlyClosedWithFallbackAndStillBlocks811() {
        assertThat(legacyReserve()).isTrue();
        jdbc.update("UPDATE ai_explanations SET created_at=?,request_payload=CAST(? AS JSONB) WHERE result_id=698",Timestamp.from(Instant.now().minusSeconds(120)),mapper.writeValueAsString(Map.of("values",Map.of(),"fallback","이전 생성 결과는 확인하지 못했습니다.")));
        single.recover();spent();assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_explanations WHERE result_id=698",String.class)).isEqualTo("DONE");
    }
    boolean attempt() { try { single.regenerate(owner,811); return true; } catch(ProductException e) { assertThat(e.code()).isEqualTo("INSIGHT_ATTEMPT_SPENT"); return false; } }
    @Test void timeoutIsTerminalAndRestartDoesNotRefund() {
        delay=900; single.regenerate(owner,811); spent();
        try(var restarted=new InsightRegeneration(jdbc,mapper,facts,generation,details,tx,true,false,false,Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> restarted.regenerate(owner,811)).isInstanceOf(ProductException.class); restarted.recover();
        }
        assertThat(calls).hasValue(1); assertThat(state()).isEqualTo("DONE");
        assertThat(insights.insight(owner,811).orElseThrow().isFallback()).isTrue();
    }
    @Test void upstreamFailureAndInvalidDraftNeverRetry() {
        reply=reply.replace("{{qty_p100}}","999999");
        single.regenerate(owner,811); spent(); assertThat(calls).hasValue(1);
        assertThat(insights.insight(owner,811).orElseThrow().mode()).isEqualTo("FIXED");
    }
    @Test void unavailableAiAlsoConsumesAttempt() {
        httpStatus=501; single.regenerate(owner,811); spent();
        assertThat(calls).hasValue(1); assertThat(insights.insight(owner,811).orElseThrow().mode()).isEqualTo("FIXED");
    }
    @Test void saveFailureAfterHttpCannotRefundCommittedBudget() {
        jdbc.execute("ALTER TABLE ai_explanations ADD CONSTRAINT reject_single_text CHECK (rendered_text IS NULL)");
        try { assertThatThrownBy(() -> single.regenerate(owner,811)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE ai_explanations DROP CONSTRAINT reject_single_text"); }
        assertThat(marker()).isNotNull(); assertThat(state()).isEqualTo("PROCESSING"); spent(); assertThat(calls).hasValue(1);
        jdbc.update("UPDATE ai_explanations SET created_at=? WHERE result_id=811",Timestamp.from(Instant.now().minusSeconds(120)));
        single.recover(); single.recover(); spent();
        assertThat(state()).isEqualTo("DONE"); assertThat(calls).hasValue(1);
    }
    @Test void callerRollbackDoesNotRefundIndependentClaim() {
        tx.executeWithoutResult(s -> { single.regenerate(owner,811); s.setRollbackOnly(); });
        assertThat(marker()).isNotNull(); assertThat(state()).isEqualTo("PROCESSING"); spent(); assertThat(calls).hasValue(1);
    }
    @Test void crashAfterClaimBeforeHttpExpiresWithoutCallingAfterRestart() {
        doThrow(new AssertionError("simulated process exit before HTTP")).when(engine).generate(anyLong(),any());
        assertThatThrownBy(() -> single.regenerate(owner,811)).isInstanceOf(AssertionError.class);
        doAnswer(a -> local.generate(a.getArgument(0),a.getArgument(1))).when(engine).generate(anyLong(),any());
        jdbc.update("UPDATE ai_explanations SET created_at=? WHERE result_id=811",Timestamp.from(Instant.now().minusSeconds(120)));
        single.recover(); single.recover(); spent();
        assertThat(state()).isEqualTo("DONE"); assertThat(calls).hasValue(0); assertThat(marker()).isNotNull();
    }
    @Test void existingPaidOrCompletedExplanationCannotBeRegenerated() {
        jdbc.update("UPDATE ai_explanations SET cost_usd=0.001 WHERE result_id=811");
        spent(); assertThat(calls).hasValue(0); assertThat(marker()).isNull();
    }
    @Test void malformedSnapshotAndMissingRiskDoNotSpendAttempt() {
        input(p -> p.remove("policy")); unavailable();
    }
    @Test void absentRiskEvidenceDoesNotBecomeInsufficientSample() {
        jdbc.update("UPDATE calculation_results SET risk_sample_size=NULL,risk_basis=NULL,pre_arrival_risk=NULL WHERE id=811"); unavailable();
    }
    @Test void dateOrMultiplierDriftBlocksBeforeHttp() {
        input(p -> p.put("today","2026-09-02")); unavailable();
    }
    @Test void wrongMultiplierIsRejected() {
        input(p -> ((ObjectNode)p.path("policy")).put("multiplier",2)); unavailable();
    }
    @Test void missingScenariosOrNonOkStatusCannotCall() {
        jdbc.update("DELETE FROM scenario_results WHERE result_id=811 AND scenario_type='P80'"); unavailable();
    }
    @Test void nonOkResultCannotCall() { jdbc.update("UPDATE calculation_results SET status='NOT_NEEDED' WHERE id=811"); unavailable(); }
    @Test void noDateAndEstimatedStartAndMethodFallbackAreReconstructed() {
        jdbc.update("UPDATE calculation_results SET start_date=?,method_reason='기본 방식 A 사용 불가로 B 사용' WHERE id=811",LocalDate.of(2026,9,2));
        input(p -> { p.put("today","2026-09-02"); ((ObjectNode)p.path("policy")).put("defaultMethod","A"); p.putArray("noDateReceipts").addObject().put("productId",product).put("quantity",7); });
        single.regenerate(owner,811);
        assertThat(sent.path("input").path("methodFallback").asBoolean()).isTrue();
        assertThat(sent.path("input").path("startStockEstimated").asBoolean()).isTrue();
        assertThat(sent.path("input").path("hasNoDateReceipts").asBoolean()).isTrue();
    }
    @Test void lowGradeWithoutBoundsExplainsOnlyAvailableStoredFacts() {
        jdbc.update("UPDATE calculation_results SET valid_observation_days=10,risk_basis=NULL,pre_arrival_risk=NULL WHERE id=811");
        jdbc.update("UPDATE scenario_results SET post_arrival_risk=NULL WHERE result_id=811");
        single.regenerate(owner,811);
        assertThat(calls).hasValue(1);
        assertThat(sent.path("input").path("riskStatus").asText()).isEqualTo("LOW_GRADE");
        assertThat(sent.path("input").path("placeholders").toString())
                .contains("qty_p100").doesNotContain("forecast_range", "risk_pre", "risk_post");
        assertThat(insights.insight(owner,811).orElseThrow().text()).doesNotContain("{{", "~", "%");
    }
    @Test void lowGradeWithOnlyOneStoredBoundRemainsUnavailable() {
        jdbc.update("UPDATE calculation_results SET valid_observation_days=10,risk_basis=NULL,pre_arrival_risk=NULL,forecast_range_low=20 WHERE id=811");
        jdbc.update("UPDATE scenario_results SET post_arrival_risk=NULL WHERE result_id=811");
        unavailable();
    }
    @Test void lowGradeWithStoredBoundsDoesNotInventRiskPercentages() {
        jdbc.update("UPDATE calculation_results SET valid_observation_days=10,risk_basis=NULL,pre_arrival_risk=NULL,forecast_range_low=20,forecast_range_high=40 WHERE id=811");
        jdbc.update("UPDATE scenario_results SET post_arrival_risk=NULL WHERE result_id=811");
        single.regenerate(owner,811);
        assertThat(sent.path("input").path("riskStatus").asText()).isEqualTo("LOW_GRADE");
        assertThat(sent.path("input").path("placeholders").toString()).contains("forecast_range_low").doesNotContain("risk_pre","risk_post");
    }
    @Test void insufficientSampleIsRestoredOnlyFromCompleteNullRiskEvidence() {
        jdbc.update("UPDATE calculation_results SET risk_basis=NULL,pre_arrival_risk=NULL,risk_sample_size=0 WHERE id=811");
        jdbc.update("UPDATE scenario_results SET post_arrival_risk=NULL WHERE result_id=811");
        single.regenerate(owner,811); assertThat(sent.path("input").path("riskStatus").asText()).isEqualTo("INSUFFICIENT_SAMPLE");
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="IPGO_INSIGHT_LOCAL_FASTAPI",matches="true")
    void restoredHistoricalCatalogPassesRealFastApiValidatorWithFakeLlm() {
        var contract=new InsightEngineClient("http://ipgo-insight-fake-api:8000",Duration.ofSeconds(2),mapper);
        try {
            doAnswer(a -> contract.generate(a.getArgument(0),a.getArgument(1))).when(engine).generate(anyLong(),any());
            single.regenerate(owner,811);
            var result=insights.insight(owner,811).orElseThrow();
            assertThat(result.mode()).isEqualTo("AI"); assertThat(result.isFallback()).isFalse();
            assertThat(result.text()).contains("100개","40개","10%");
            assertThat(calls).hasValue(0); spent();
        } finally { contract.close(); }
    }
}
