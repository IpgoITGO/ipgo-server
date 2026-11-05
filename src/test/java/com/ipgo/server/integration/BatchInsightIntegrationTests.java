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

/** Synthetic fixed manifest; local HTTP or opt-in internal FastAPI/FakeClient only. */
@SpringBootTest(properties={"ipgo.insight.batch-enabled=true","ipgo.insight.single-result-enabled=false",
        "ipgo.insight.enabled=false","ipgo.insight.worker-enabled=false"})
@AutoConfigureMockMvc
class BatchInsightIntegrationTests extends DatabaseIntegrationTest {
    static final String BATCH="master-current-20261105", BASE="/api/insight-batches/"+BATCH;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired TransactionTemplate tx;
    @Autowired StoredInsightFacts facts;
    @Autowired InsightGeneration generation;
    @Autowired ProductDetailService details;
    @Autowired InsightBatch batch;
    @Autowired InsightService insights;
    @Autowired MockMvc mvc;
    @Autowired WarehouseService warehouses;
    @MockitoBean InsightEngineClient engine;
    HttpServer http;
    InsightEngineClient local;
    AtomicInteger calls;
    volatile JsonNode sent;
    volatile String reply;
    volatile long delay;
    volatile int httpStatus;
    long warehouse,run;

    @BeforeEach void setup() throws Exception {
        UploadTestDatabase.clear(jdbc);
        calls=new AtomicInteger(); delay=0; httpStatus=200;
        reply=mapper.writeValueAsString(Map.of("validation","PASSED","draft","{{qty_p100}}개 입고 시 {{ending_p100}}개가 남습니다.",
                "promptVersion","batch-test-v1","latencyMs",1,"costUsd",0));
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
        if(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=1",Integer.class)==0) {
            long first=UploadTestDatabase.newUser(jdbc,UUID.randomUUID()+"@example.test");
            if(first!=1) jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(1,?,'test-only')",UUID.randomUUID()+"@example.test");
        }
        warehouse=warehouses.lockDefault(1).getId();
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',?,'DONE','test',JSON '{}',CURRENT_TIMESTAMP)",warehouse,LocalDate.of(2026,9,1));
        run=jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs",Long.class);
        for(int product=32;product<=37;product++) {
            long id=product+779;
            jdbc.update("INSERT INTO products(id,user_id,sku_barcode,sku_name) VALUES(?,1,?,'비공개 상품명')",product,"batch-"+product);
            var input=mapper.valueToTree(Map.of("userId",1,"warehouseId",warehouse,"productIds",List.of(product),
                    "asOfDate","2026-09-01","today","2026-09-01","policy",Map.of("defaultMethod","B","multiplier",new BigDecimal("1.05")),"noDateReceipts",List.of()));
            jdbc.update("""
                    INSERT INTO calculation_results(id,run_id,product_id,origin_date,start_date,method,multiplier,horizon_days,
                    start_stock_qty,planned_receipt_qty,forecast_outbound_qty,recommended_qty,status,input_snapshot,
                    pre_arrival_risk,risk_sample_size,risk_basis,valid_observation_days)
                    VALUES(?,?,?,DATE '2026-09-01',DATE '2026-09-01','B',1.05,14,10.9,5,95.1,100,'OK',CAST(? AS JSONB),0.1,40,'POOLED',40)
                    """,id,run,product,mapper.writeValueAsString(input));
            var original=mapper.readTree(getClass().getResourceAsStream("/insight/result.json"));
            for(JsonNode s:original.path("scenarios")) jdbc.update("""
                    INSERT INTO scenario_results(result_id,scenario_type,scenario_qty,is_duplicate,expected_ending_stock,
                    unmet_demand_qty,post_arrival_risk,daily_projection) VALUES(?,?,?,?,?,?,?,JSON '[]')
                    """,id,s.path("type").asText(),s.path("qty").asLong(),false,s.path("expectedEndingStock").decimalValue(),
                    s.path("unmetDemandQty").decimalValue(),s.path("postArrivalRisk").decimalValue());
            jdbc.update("INSERT INTO ai_explanations(result_id,mode,status,request_payload,validation_passed,reason_code,versions) VALUES(?,'NONE','DISABLED',JSON '{}',TRUE,'LLM_DISABLED',JSON '{}')",id);
        }
        jdbc.update("UPDATE ai_explanations SET mode='LLM',status='DONE',rendered_text='기존811',reason_code=NULL WHERE result_id=811");
    }
    @AfterEach void cleanup() { if(http!=null) http.stop(0); }
    String marker(long id) { return jdbc.queryForObject("SELECT output_path FROM ai_explanations WHERE result_id=?",String.class,id); }
    String state(long id) { return jdbc.queryForObject("SELECT status FROM ai_explanations WHERE result_id=?",String.class,id); }
    void generate(long id) { batch.generate(1,BATCH,id); }
    void rejected(String code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProductException.class,e -> assertThat(e.code()).isEqualTo(code));
    }
    void unspent() { assertThat(calls).hasValue(0); assertThat(batch.preview(1,BATCH).reservedAttempts()).isZero(); }
    InsightBatch instance(boolean enabled,boolean automatic,boolean worker,boolean single) {
        return new InsightBatch(jdbc,mapper,facts,generation,details,tx,enabled,automatic,worker,single,Duration.ofMillis(500));
    }
    void mutateInput(java.util.function.Consumer<ObjectNode> change) {
        JsonNode p=mapper.readTree(jdbc.queryForObject("SELECT input_snapshot FROM calculation_results WHERE id=812",String.class));
        if(p.isString()) p=mapper.readTree(p.asText());
        change.accept((ObjectNode)p);
        jdbc.update("UPDATE calculation_results SET input_snapshot=CAST(? AS JSONB) WHERE id=812",mapper.writeValueAsString(p));
    }

    @Test void previewIsReadOnlyAndFixedToExactlyFiveTargets() throws Exception {
        mvc.perform(get(BASE).with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk()).andExpect(jsonPath("maximumCalls").value(5))
                .andExpect(jsonPath("remainingAttempts").value(5)).andExpect(jsonPath("targets.length()").value(5))
                .andExpect(jsonPath("targets[0].resultId").value(812)).andExpect(jsonPath("targets[4].resultId").value(816));
        batch.preview(1,BATCH);unspent();assertThat(marker(812)).isNull();
    }
    @Test void oneExplicitPostPersistsAndServesExistingInsightApiWithoutTouching811() throws Exception {
        mvc.perform(post(BASE+"/results/812/generate").with(jwt().jwt(j -> j.subject("1"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("mode").value("AI"));
        mvc.perform(get("/api/results/812/insight").with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk()).andExpect(jsonPath("text").value("100개 입고 시 40개가 남습니다."));
        assertThat(calls).hasValue(1);assertThat(marker(812)).isEqualTo("batch/"+BATCH+"/812/attempt-1");
        assertThat(insights.insight(1,811).orElseThrow().text()).isEqualTo("기존811");assertThat(marker(811)).isNull();
        assertThat(sent.path("resultId").asLong()).isEqualTo(812);
        assertThat(sent.toString()).doesNotContain("비공개 상품명","warehouseId","userId","unitCost");
    }
    @Test void lifetimeCapIsFiveAndNewServiceCannotRechargeIt() {
        for(long id=812;id<=816;id++) generate(id);
        assertThat(calls).hasValue(5);assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(5);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
        try(var restarted=instance(true,false,false,false)) {
            for(long id=812;id<=816;id++) { long target=id;rejected("INSIGHT_ATTEMPT_SPENT",()->restarted.generate(1,BATCH,target)); }
        }
        rejected("FORBIDDEN",()->generate(817));assertThat(calls).hasValue(5);
    }
    @Test void outOfScope811HistoricalAndArbitraryIdCannotSpend() {
        for(long id:List.of(811L,698L,817L,999999L))rejected("FORBIDDEN",()->generate(id));unspent();
    }
    @Test void wrongOwnerAndBatchCannotReadOrWriteScope() throws Exception {
        mvc.perform(get(BASE).with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/results/812/generate").with(jwt().jwt(j -> j.subject("2"))).with(csrf())).andExpect(status().isForbidden());
        rejected("FORBIDDEN",()->batch.generate(1,"other",812));unspent();
    }
    @Test void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());unspent();
    }
    @Test void callerCannotOverrideTargetsLimitOrInputInBody() throws Exception {
        for(String body:List.of("{}","{\"resultId\":811,\"limit\":31}","[]","1"))
            mvc.perform(post(BASE+"/results/812/generate").contentType("application/json").content(body)
                    .with(jwt().jwt(j -> j.subject("1"))).with(csrf())).andExpect(status().isBadRequest());
        unspent();
    }
    @Test void disabledModeStillAllowsReadOnlyPreviewButNeverPost() {
        try(var disabled=instance(false,false,false,false)) {
            assertThat(disabled.preview(1,BATCH).enabled()).isFalse();
            rejected("INSIGHT_BATCH_DISABLED",()->disabled.generate(1,BATCH,812));disabled.recover();
        }unspent();
    }
    @Test void batchCannotCoexistWithGlobalWorkerOrSingleMode() {
        for(boolean[] v:List.of(new boolean[]{true,false,false},new boolean[]{false,true,false},new boolean[]{false,false,true}))
            assertThatThrownBy(()->instance(true,v[0],v[1],v[2])).isInstanceOf(IllegalArgumentException.class);
        unspent();
    }
    @Test void globalTickRemainsOffEvenWhenBatchIsEnabled() { generation.tick();unspent(); }
    @Test void ownershipTransferOrWrongProductCannotCall() {
        jdbc.update("INSERT INTO products(id,user_id,sku_barcode,sku_name) VALUES(40,1,'outside','고정 범위 밖')");
        jdbc.update("UPDATE calculation_results SET product_id=40 WHERE id=812");
        rejected("FORBIDDEN",()->generate(812));unspent();
    }
    @Test void missingBudgetRowBlocksAllCallsWithoutRecreatingIt() {
        jdbc.update("DELETE FROM ai_explanations WHERE result_id=816");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(812));unspent();
    }
    @Test void newerRunDoesNotReplaceFixedTarget() {
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',DATE '2026-09-02','DONE','test',JSON '{}',CURRENT_TIMESTAMP)",warehouse);
        rejected("INSIGHT_RESULT_STALE",()->generate(812));unspent();
    }
    @Test void existingGeneratedOrPaidOrClaimedTargetIsSkipped() {
        jdbc.update("UPDATE ai_explanations SET cost_usd=0.001 WHERE result_id=812");
        rejected("INSIGHT_ATTEMPT_SPENT",()->generate(812));unspent();
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isEqualTo(4);
    }
    @Test void incompleteInputIsExcludedBeforeReservation() {
        mutateInput(p->p.remove("policy"));rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(812));unspent();
        assertThat(batch.preview(1,BATCH).targets().getFirst().state()).isEqualTo("INSIGHT_INPUT_UNAVAILABLE");
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isEqualTo(4);
    }
    @Test void excludedResultIsNeverSent() {
        jdbc.update("UPDATE calculation_results SET status='EXCLUDED' WHERE id=812");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(812));unspent();
    }
    @Test void missingScenarioIsNeverInvented() {
        jdbc.update("DELETE FROM scenario_results WHERE result_id=812 AND scenario_type='P80'");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(812));unspent();
    }
    @Test void upstreamFailureStopsEntireBatchWithNoRetry() {
        httpStatus=501;generate(812);rejected("INSIGHT_ATTEMPT_SPENT",()->generate(812));
        rejected("INSIGHT_BATCH_STOPPED",()->generate(813));
        assertThat(calls).hasValue(1);assertThat(batch.preview(1,BATCH).gate()).isEqualTo("STOPPED_AFTER_FAILURE");
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
        assertThat(insights.insight(1,812).orElseThrow().isFallback()).isTrue();
    }
    @Test void validationFailureAlsoStopsBatch() {
        reply=reply.replace("{{qty_p100}}","999999");generate(812);
        rejected("INSIGHT_BATCH_STOPPED",()->generate(813));assertThat(calls).hasValue(1);
    }
    @Test void timeoutCannotResumeOrRetry() {
        delay=900;generate(812);rejected("INSIGHT_BATCH_STOPPED",()->generate(813));
        rejected("INSIGHT_ATTEMPT_SPENT",()->generate(812));assertThat(calls).hasValue(1);
    }
    @Test void saveFailureAfterHttpKeepsCommittedClaimAndStopsOtherTargets() {
        jdbc.execute("ALTER TABLE ai_explanations ADD CONSTRAINT reject_batch_text CHECK (result_id<>812 OR rendered_text IS NULL)");
        try { assertThatThrownBy(()->generate(812)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE ai_explanations DROP CONSTRAINT reject_batch_text"); }
        assertThat(state(812)).isEqualTo("PROCESSING");assertThat(marker(812)).isNotNull();
        rejected("INSIGHT_BATCH_STOPPED",()->generate(813));assertThat(calls).hasValue(1);
    }
    @Test void claimFailureRollsBackAllReservationBeforeAnyHttp() {
        jdbc.execute("ALTER TABLE ai_explanations ADD CONSTRAINT reject_batch_claim CHECK (result_id<>812 OR status<>'PROCESSING')");
        try { assertThatThrownBy(()->generate(812)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE ai_explanations DROP CONSTRAINT reject_batch_claim"); }
        unspent();assertThat(marker(812)).isNull();
    }
    @Test void crashBeforeHttpSpendsAttemptAndRecoveryNeverCallsProvider() {
        doThrow(new AssertionError("synthetic crash")).when(engine).generate(anyLong(),any());
        assertThatThrownBy(()->generate(812)).isInstanceOf(AssertionError.class);
        rejected("INSIGHT_BATCH_STOPPED",()->generate(813));
        jdbc.update("UPDATE ai_explanations SET created_at=? WHERE result_id=812",Timestamp.from(Instant.now().minusSeconds(120)));
        batch.recover();batch.recover();rejected("INSIGHT_ATTEMPT_SPENT",()->generate(812));
        rejected("INSIGHT_BATCH_STOPPED",()->generate(813));assertThat(calls).hasValue(0);
        assertThat(state(812)).isEqualTo("DONE");assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(1);
    }
    @Test void callerRollbackCannotRefundIndependentReservation() {
        tx.executeWithoutResult(s->{ generate(812);s.setRollbackOnly(); });
        assertThat(marker(812)).isNotNull();rejected("INSIGHT_ATTEMPT_SPENT",()->generate(812));assertThat(calls).hasValue(1);
    }
    @Test void parallelDuplicatePostsAcrossServiceInstancesOnlySendOnce() throws Exception {
        delay=100;
        try(var other=instance(true,false,false,false);var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();try{generate(812);}catch(ProductException e){}return null;});
            var b=pool.submit(()->{start.await();try{other.generate(1,BATCH,812);}catch(ProductException e){}return null;});
            start.countDown();a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(calls).hasValue(1);assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(1);
    }
    @Test void differentTargetCannotRunWhileAnotherRequestIsInFlight() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(a->{entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return local.generate(a.getArgument(0),a.getArgument(1));}).when(engine).generate(anyLong(),any());
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var a=pool.submit(()->generate(812));
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try { rejected("INSIGHT_BATCH_STOPPED",()->generate(813));assertThat(marker(813)).isNull(); }
            finally { release.countDown(); }
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        generate(813);assertThat(calls).hasValue(2);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="IPGO_INSIGHT_LOCAL_FASTAPI",matches="true")
    void allFiveRestoredInputsPassRealFastApiValidatorWithFakeLlm() {
        var real=new InsightEngineClient("http://ipgo-insight-fake-api:8000",Duration.ofSeconds(5),mapper);
        doAnswer(a->real.generate(a.getArgument(0),a.getArgument(1))).when(engine).generate(anyLong(),any());
        for(long id=812;id<=816;id++) { generate(id);assertThat(insights.insight(1,id).orElseThrow().mode()).isEqualTo("AI"); }
        assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(5);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
    }
}
