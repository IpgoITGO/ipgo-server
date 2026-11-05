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
    @Autowired InsightBatchBudget budget;
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
    java.util.Map<Long,com.ipgo.server.dto.response.ProductDetail> oldViews;

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
        oldViews=new java.util.HashMap<>();
        for(long product=33;product<=37;product++) oldViews.put(product,details.detail(1,product,null,null));
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',DATE '2026-09-01','DONE','test',JSON '{}',CURRENT_TIMESTAMP)",warehouse);
        run=jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs",Long.class);
        for(long product=33;product<=37;product++) {
            long original=product+779,next=product+1888;
            jdbc.update("""
                    INSERT INTO calculation_results(id,run_id,product_id,origin_date,start_date,method,multiplier,horizon_days,
                    start_stock_qty,planned_receipt_qty,forecast_outbound_qty,recommended_qty,status,input_snapshot,
                    pre_arrival_risk,risk_sample_size,risk_basis,valid_observation_days)
                    SELECT ?,?,product_id,origin_date,start_date,method,multiplier,horizon_days,start_stock_qty,planned_receipt_qty,
                    forecast_outbound_qty,recommended_qty,status,input_snapshot,pre_arrival_risk,risk_sample_size,risk_basis,valid_observation_days
                    FROM calculation_results WHERE id=?
                    """,next,run,original);
            jdbc.update("""
                    INSERT INTO scenario_results(result_id,scenario_type,scenario_qty,is_duplicate,expected_ending_stock,
                    unmet_demand_qty,post_arrival_risk,daily_projection)
                    SELECT ?,scenario_type,scenario_qty,is_duplicate,expected_ending_stock,unmet_demand_qty,post_arrival_risk,daily_projection
                    FROM scenario_results WHERE result_id=?
                    """,next,original);
            jdbc.update("INSERT INTO ai_explanations(result_id,mode,status,request_payload,validation_passed,reason_code,versions) VALUES(?,'NONE','DISABLED',JSON '{}',TRUE,'LLM_DISABLED',JSON '{}')",next);
        }
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
        return new InsightBatch(jdbc,mapper,facts,generation,details,budget,tx,enabled,automatic,worker,single,Duration.ofMillis(500));
    }
    void mutateInput(java.util.function.Consumer<ObjectNode> change) {
        JsonNode p=mapper.readTree(jdbc.queryForObject("SELECT input_snapshot FROM calculation_results WHERE id=1921",String.class));
        if(p.isString()) p=mapper.readTree(p.asText());
        change.accept((ObjectNode)p);
        jdbc.update("UPDATE calculation_results SET input_snapshot=CAST(? AS JSONB) WHERE id=1921",mapper.writeValueAsString(p));
    }

    @Test void previewIsReadOnlyAndFixedToExactlyFiveTargets() throws Exception {
        mvc.perform(get(BASE).with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk()).andExpect(jsonPath("maximumCalls").value(5))
                .andExpect(jsonPath("remainingAttempts").value(5)).andExpect(jsonPath("targets.length()").value(5))
                .andExpect(jsonPath("targets[0].resultId").value(1921)).andExpect(jsonPath("targets[0].reservedByAnchorResultId").isEmpty()).andExpect(jsonPath("targets[4].resultId").value(1925));
        batch.preview(1,BATCH);unspent();assertThat(marker(1921)).isNull();
    }
    @Test void oneExplicitPostPersistsAndServesExistingInsightApiWithoutTouching811() throws Exception {
        mvc.perform(post(BASE+"/results/1921/generate").with(jwt().jwt(j -> j.subject("1"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("mode").value("AI"));
        mvc.perform(get("/api/results/1921/insight").with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk()).andExpect(jsonPath("text").value("100개 입고 시 40개가 남습니다."));
        assertThat(calls).hasValue(1);assertThat(marker(1921)).isEqualTo("batch/"+BATCH+"/1921/attempt-1");
        assertThat(insights.insight(1,811).orElseThrow().text()).isEqualTo("기존811");assertThat(marker(811)).isNull();
        assertThat(sent.path("resultId").asLong()).isEqualTo(1921);
        assertThat(sent.toString()).doesNotContain("비공개 상품명","warehouseId","userId","unitCost");
    }
    @Test void lifetimeCapIsFiveAndNewServiceCannotRechargeIt() {
        for(long id=1921;id<=1925;id++) generate(id);
        assertThat(calls).hasValue(5);assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(5);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
        try(var restarted=instance(true,false,false,false)) {
            for(long id=1921;id<=1925;id++) { long target=id;rejected("INSIGHT_ATTEMPT_SPENT",()->restarted.generate(1,BATCH,target)); }
        }
        rejected("FORBIDDEN",()->generate(817));assertThat(calls).hasValue(5);
    }
    @Test void outOfScope811HistoricalAndArbitraryIdCannotSpend() {
        for(long id:List.of(811L,698L,812L,813L,814L,815L,816L,817L,999999L))rejected("FORBIDDEN",()->generate(id));unspent();
    }
    @Test void wrongOwnerAndBatchCannotReadOrWriteScope() throws Exception {
        mvc.perform(get(BASE).with(jwt().jwt(j -> j.subject("2")))).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/results/1921/generate").with(jwt().jwt(j -> j.subject("2"))).with(csrf())).andExpect(status().isForbidden());
        rejected("FORBIDDEN",()->batch.generate(1,"other",1921));unspent();
    }
    @Test void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());unspent();
    }
    @Test void callerCannotOverrideTargetsLimitOrInputInBody() throws Exception {
        for(String body:List.of("{}","{\"resultId\":811,\"limit\":31}","[]","1"))
            mvc.perform(post(BASE+"/results/1921/generate").contentType("application/json").content(body)
                    .with(jwt().jwt(j -> j.subject("1"))).with(csrf())).andExpect(status().isBadRequest());
        unspent();
    }
    @Test void disabledModeStillAllowsReadOnlyPreviewButNeverPost() {
        try(var disabled=instance(false,false,false,false)) {
            assertThat(disabled.preview(1,BATCH).enabled()).isFalse();
            rejected("INSIGHT_BATCH_DISABLED",()->disabled.generate(1,BATCH,1921));disabled.recover();
        }unspent();
    }
    @Test void batchCannotCoexistWithGlobalWorkerOrSingleMode() {
        for(boolean[] v:List.of(new boolean[]{true,false,false},new boolean[]{false,true,false},new boolean[]{false,false,true}))
            assertThatThrownBy(()->instance(true,v[0],v[1],v[2])).isInstanceOf(IllegalArgumentException.class);
        unspent();
    }
    @Test void globalTickRemainsOffEvenWhenBatchIsEnabled() { generation.tick();unspent(); }
    @Test void wrongFixedProductCannotCall() {
        jdbc.update("INSERT INTO products(id,user_id,sku_barcode,sku_name) VALUES(40,1,'outside','고정 범위 밖')");
        jdbc.update("UPDATE calculation_results SET product_id=40 WHERE id=1921");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1921));unspent();
    }
    @Test void missingBudgetRowBlocksAllCallsWithoutRecreatingIt() {
        jdbc.update("DELETE FROM ai_explanations WHERE result_id=1925");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1921));unspent();
    }
    @Test void newerRunDoesNotReplaceFixedTarget() {
        jdbc.update("INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at) VALUES (?,'CALCULATE',DATE '2026-09-02','DONE','test',JSON '{}',CURRENT_TIMESTAMP)",warehouse);
        rejected("INSIGHT_RESULT_STALE",()->generate(1921));unspent();
    }
    @Test void unpairedPaidNewRowFailsClosed() {
        jdbc.update("UPDATE ai_explanations SET cost_usd=0.001 WHERE result_id=1921");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1921));unspent();
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
    }
    @Test void incompleteInputIsExcludedBeforeReservation() {
        mutateInput(p->p.remove("policy"));rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1921));unspent();
        assertThat(batch.preview(1,BATCH).targets().getFirst().state()).isEqualTo("INSIGHT_INPUT_UNAVAILABLE");
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isEqualTo(4);
    }
    @Test void excludedResultIsNeverSent() {
        jdbc.update("UPDATE calculation_results SET status='EXCLUDED' WHERE id=1921");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1921));unspent();
    }
    @Test void missingScenarioIsNeverInvented() {
        jdbc.update("DELETE FROM scenario_results WHERE result_id=1921 AND scenario_type='P80'");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1921));unspent();
    }
    @Test void upstreamFailureStopsEntireBatchWithNoRetry() {
        httpStatus=501;generate(1921);rejected("INSIGHT_ATTEMPT_SPENT",()->generate(1921));
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));
        assertThat(calls).hasValue(1);assertThat(batch.preview(1,BATCH).gate()).isEqualTo("STOPPED_AFTER_FAILURE");
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
        assertThat(insights.insight(1,1921).orElseThrow().isFallback()).isTrue();
    }
    @Test void validationFailureAlsoStopsBatch() {
        reply=reply.replace("{{qty_p100}}","999999");generate(1921);
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));assertThat(calls).hasValue(1);
    }
    @Test void timeoutCannotResumeOrRetry() {
        delay=900;generate(1921);rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));
        rejected("INSIGHT_ATTEMPT_SPENT",()->generate(1921));assertThat(calls).hasValue(1);
    }
    @Test void saveFailureAfterHttpKeepsCommittedClaimAndStopsOtherTargets() {
        jdbc.execute("ALTER TABLE ai_explanations ADD CONSTRAINT reject_batch_text CHECK (result_id<>1921 OR rendered_text IS NULL)");
        try { assertThatThrownBy(()->generate(1921)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE ai_explanations DROP CONSTRAINT reject_batch_text"); }
        assertThat(state(1921)).isEqualTo("PROCESSING");assertThat(marker(1921)).isNotNull();
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));assertThat(calls).hasValue(1);
    }
    @Test void claimFailureRollsBackAllReservationBeforeAnyHttp() {
        jdbc.execute("ALTER TABLE ai_explanations ADD CONSTRAINT reject_batch_claim CHECK (result_id<>1921 OR status<>'PROCESSING')");
        try { assertThatThrownBy(()->generate(1921)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE ai_explanations DROP CONSTRAINT reject_batch_claim"); }
        unspent();assertThat(marker(1921)).isNull();
    }
    @Test void crashBeforeHttpSpendsAttemptAndRecoveryNeverCallsProvider() {
        doThrow(new AssertionError("synthetic crash")).when(engine).generate(anyLong(),any());
        assertThatThrownBy(()->generate(1921)).isInstanceOf(AssertionError.class);
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));
        jdbc.update("UPDATE ai_explanations SET created_at=? WHERE result_id=1921",Timestamp.from(Instant.now().minusSeconds(120)));
        batch.recover();batch.recover();rejected("INSIGHT_ATTEMPT_SPENT",()->generate(1921));
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));assertThat(calls).hasValue(0);
        assertThat(state(1921)).isEqualTo("DONE");assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(1);
    }
    @Test void callerRollbackCannotRefundIndependentReservation() {
        tx.executeWithoutResult(s->{ generate(1921);s.setRollbackOnly(); });
        assertThat(marker(1921)).isNotNull();rejected("INSIGHT_ATTEMPT_SPENT",()->generate(1921));assertThat(calls).hasValue(1);
    }
    @Test void parallelDuplicatePostsAcrossServiceInstancesOnlySendOnce() throws Exception {
        delay=100;
        try(var other=instance(true,false,false,false);var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();try{generate(1921);}catch(ProductException e){}return null;});
            var b=pool.submit(()->{start.await();try{other.generate(1,BATCH,1921);}catch(ProductException e){}return null;});
            start.countDown();a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(calls).hasValue(1);assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(1);
    }
    @Test void differentTargetCannotRunWhileAnotherRequestIsInFlight() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(a->{entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return local.generate(a.getArgument(0),a.getArgument(1));}).when(engine).generate(anyLong(),any());
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var a=pool.submit(()->generate(1921));
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try { rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));assertThat(marker(1922)).isNull(); }
            finally { release.countDown(); }
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        generate(1922);assertThat(calls).hasValue(2);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="IPGO_INSIGHT_LOCAL_FASTAPI",matches="true")
    void allFiveRestoredInputsPassRealFastApiValidatorWithFakeLlm() {
        var real=new InsightEngineClient("http://ipgo-insight-fake-api:8000",Duration.ofSeconds(5),mapper);
        doAnswer(a->real.generate(a.getArgument(0),a.getArgument(1))).when(engine).generate(anyLong(),any());
        for(long id=1921;id<=1925;id++) { generate(id);assertThat(insights.insight(1,id).orElseThrow().mode()).isEqualTo("AI"); }
        assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(5);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
    }

    LegacyInsightBatch legacy() {
        // Frozen read results simulate a concurrent/rolled-back old instance whose current-view check passes.
        // Its reservation/gate/recovery code is copied from actual deployed 6fbb792, not reimplemented.
        var views=mock(ProductDetailService.class);
        when(views.detail(eq(1L),anyLong(),isNull(),isNull())).thenAnswer(a->oldViews.get(a.<Long>getArgument(1)));
        return new LegacyInsightBatch(jdbc,mapper,facts,generation,views,tx,true,false,false,false,Duration.ofMillis(500));
    }
    @Test void delegationPreservesHistoricalPublicDtoAndOriginalVersionMetadata() {
        jdbc.update("UPDATE ai_explanations SET versions=CAST(? AS JSONB) WHERE result_id=812",mapper.writeValueAsString(Map.of("template","spring-1","fixture","keep")));
        var before=insights.insight(1,812).orElseThrow();
        generate(1921);
        assertThat(insights.insight(1,812).orElseThrow()).isEqualTo(before);
        assertThat(state(812)).isEqualTo("SKIPPED");assertThat(marker(812)).isEqualTo("batch/"+BATCH+"/812/attempt-1");
        String versions=jdbc.queryForObject("SELECT CAST(versions AS VARCHAR) FROM ai_explanations WHERE result_id=812",String.class);
        assertThat(versions).contains("spring-1","keep","batchBudgetDelegate","1921");
        assertThat(insights.insight(1,811).orElseThrow().text()).isEqualTo("기존811");
    }
    @Test void oldCallsConsumeTheSameSlotsBeforeNewScopeStarts() {
        try(var old=legacy()) { old.generate(1,BATCH,812);old.generate(1,BATCH,813); }
        assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(2);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isEqualTo(3);
        for(long id=1921;id<=1923;id++)generate(id);
        rejected("INSIGHT_ATTEMPT_SPENT",()->generate(1924));rejected("INSIGHT_ATTEMPT_SPENT",()->generate(1925));
        assertThat(calls).hasValue(5);assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(5);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
        try(var old=legacy()) { for(long id=812;id<=816;id++){long x=id;rejected("INSIGHT_ATTEMPT_SPENT",()->old.generate(1,BATCH,x));} }
        assertThat(calls).hasValue(5);
    }
    @Test void fiveLegacyCallsLeaveNoFreshBudgetForNewScope() {
        try(var old=legacy()) { for(long id=812;id<=816;id++)old.generate(1,BATCH,id); }
        assertThat(calls).hasValue(5);
        for(long id=1921;id<=1925;id++){long x=id;rejected("INSIGHT_ATTEMPT_SPENT",()->generate(x));}
        assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(5);assertThat(calls).hasValue(5);
    }
    @Test void oldVersionAfterNewSuccessCannotSpendEvenAnotherUnusedOldSlot() {
        generate(1921);
        try(var old=legacy()) {
            rejected("INSIGHT_ATTEMPT_SPENT",()->old.generate(1,BATCH,812));
            rejected("INSIGHT_BATCH_STOPPED",()->old.generate(1,BATCH,813));
            old.recover();old.recover();
            assertThat(old.preview(1,BATCH).reservedAttempts()).isEqualTo(1);
            assertThat(old.preview(1,BATCH).remainingAttempts()).isZero();
            assertThat(old.preview(1,BATCH).gate()).isEqualTo("STOPPED_AFTER_FAILURE");
        }
        assertThat(calls).hasValue(1);assertThat(state(812)).isEqualTo("SKIPPED");
        generate(1922);assertThat(calls).hasValue(2);
    }
    @Test void legacyFailureStopsAllNewTargetsWithoutRefund() {
        httpStatus=501;
        try(var old=legacy()) { old.generate(1,BATCH,812); }
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1921));rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));
        assertThat(calls).hasValue(1);assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(1);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
    }
    @Test void newFailureAlsoStopsTheFrozenLegacyVersion() {
        httpStatus=501;generate(1921);
        try(var old=legacy()) { rejected("INSIGHT_BATCH_STOPPED",()->old.generate(1,BATCH,813)); }
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));assertThat(calls).hasValue(1);
    }
    @Test void concurrentLegacyAndNewVersionsSerializeAndStillShareOnlyFiveCalls() throws Exception {
        delay=100;
        var active=new AtomicInteger();var maximum=new AtomicInteger();
        var byId=new java.util.concurrent.ConcurrentHashMap<Long,AtomicInteger>();
        doAnswer(a->{long id=a.getArgument(0);byId.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();
            maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
            try{return local.generate(id,a.getArgument(1));}finally{active.decrementAndGet();}}).when(engine).generate(anyLong(),any());
        try(var old=legacy();var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();try{generate(1921);}catch(ProductException e){}return null;});
            var b=pool.submit(()->{start.await();try{old.generate(1,BATCH,812);}catch(ProductException e){}return null;});
            start.countDown();a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        for(long id=1921;id<=1925;id++){long x=id;try{generate(x);}catch(ProductException e){assertThat(e.code()).isEqualTo("INSIGHT_ATTEMPT_SPENT");}}
        assertThat(calls).hasValue(5);assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(5);
        assertThat(maximum).hasValue(1);assertThat(byId.values()).allSatisfy(n->assertThat(n).hasValue(1));
    }
    @Test void oldUseOnSameSkuDoesNotRestrictWhichNewTargetMayUseRemainingPool() {
        try(var old=legacy()){old.generate(1,BATCH,812);}
        generate(1921);
        assertThat(calls).hasValue(2);assertThat(marker(813)).isEqualTo("batch/"+BATCH+"/813/attempt-1");
        assertThat(batch.preview(1,BATCH).targets().getFirst().reservedByAnchorResultId()).isEqualTo(813L);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isEqualTo(3);
    }
    @Test void newInFlightBlocksDifferentOldSlot() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(a->{entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return local.generate(a.getArgument(0),a.getArgument(1));}).when(engine).generate(anyLong(),any());
        try(var old=legacy();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var a=pool.submit(()->generate(1921));assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try { rejected("INSIGHT_BATCH_STOPPED",()->old.generate(1,BATCH,813)); }
            finally { release.countDown(); }
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(calls).hasValue(1);
    }
    @Test void legacyInFlightBlocksDifferentNewSlot() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(a->{entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return local.generate(a.getArgument(0),a.getArgument(1));}).when(engine).generate(anyLong(),any());
        try(var old=legacy();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var a=pool.submit(()->old.generate(1,BATCH,812));assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try { rejected("INSIGHT_BATCH_STOPPED",()->generate(1922)); }
            finally { release.countDown(); }
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        generate(1922);assertThat(calls).hasValue(2);
    }
    @Test void atomicClaimFailureRestoresBothRowsAndVersionMetadata() {
        var before=insights.insight(1,812).orElseThrow();
        jdbc.execute("ALTER TABLE ai_explanations ADD CONSTRAINT reject_new_scope_claim CHECK (result_id<>1921 OR status<>'PROCESSING')");
        try { assertThatThrownBy(()->generate(1921)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE ai_explanations DROP CONSTRAINT reject_new_scope_claim"); }
        assertThat(state(812)).isEqualTo("DISABLED");assertThat(marker(812)).isNull();
        assertThat(insights.insight(1,812).orElseThrow()).isEqualTo(before);unspent();
    }
    @Test void anchorWriteFailureNeverReservesTheNewRow() {
        jdbc.execute("ALTER TABLE ai_explanations ADD CONSTRAINT reject_anchor_scope CHECK (result_id<>812 OR status='DISABLED')");
        try { assertThatThrownBy(()->generate(1921)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE ai_explanations DROP CONSTRAINT reject_anchor_scope"); }
        assertThat(marker(1921)).isNull();assertThat(marker(812)).isNull();unspent();
    }
    @Test void missingOriginalAnchorCannotCreateANewGrant() {
        jdbc.update("DELETE FROM ai_explanations WHERE result_id=812");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1922));unspent();
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
    }
    @Test void tamperedDelegateMetadataFailsClosedWithoutRecharging() {
        generate(1921);jdbc.update("UPDATE ai_explanations SET versions=JSON '{}' WHERE result_id=812");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1922));assertThat(calls).hasValue(1);
        assertThat(batch.preview(1,BATCH).reservedAttempts()).isEqualTo(1);
    }
    @Test void unpairedNewReservationDoesNotBecomeAnExtraBudget() {
        jdbc.update("UPDATE ai_explanations SET status='PROCESSING',output_path=? WHERE result_id=1921","batch/"+BATCH+"/1921/attempt-1");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1922));assertThat(calls).hasValue(0);
        assertThat(batch.preview(1,BATCH).remainingAttempts()).isZero();
    }
    @Test void crashAndRollbackRecoveryNeverRendersFallbackIntoHistoricalAnchor() {
        var historical=insights.insight(1,812).orElseThrow();
        doThrow(new AssertionError("synthetic crash")).when(engine).generate(anyLong(),any());
        assertThatThrownBy(()->generate(1921)).isInstanceOf(AssertionError.class);
        jdbc.update("UPDATE ai_explanations SET created_at=? WHERE result_id=1921",Timestamp.from(Instant.now().minusSeconds(120)));
        try(var old=legacy()) { old.recover();rejected("INSIGHT_BATCH_STOPPED",()->old.generate(1,BATCH,813)); }
        assertThat(state(1921)).isEqualTo("PROCESSING");assertThat(insights.insight(1,812).orElseThrow()).isEqualTo(historical);
        batch.recover();batch.recover();assertThat(state(1921)).isEqualTo("DONE");
        assertThat(insights.insight(1,812).orElseThrow()).isEqualTo(historical);assertThat(calls).hasValue(0);
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));
    }
    @Test void newRecoveryAlsoTerminatesLegacyUnknownOutcomeWithoutCallingAgain() {
        doThrow(new AssertionError("synthetic old crash")).when(engine).generate(anyLong(),any());
        try(var old=legacy()) { assertThatThrownBy(()->old.generate(1,BATCH,812)).isInstanceOf(AssertionError.class); }
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));
        jdbc.update("UPDATE ai_explanations SET created_at=? WHERE result_id=812",Timestamp.from(Instant.now().minusSeconds(120)));
        batch.recover();batch.recover();assertThat(state(812)).isEqualTo("DONE");assertThat(calls).hasValue(0);
        rejected("INSIGHT_BATCH_STOPPED",()->generate(1922));
    }
    @Test void mismatchedOriginalAnchorProductOrOwnerCannotDonateBudget() {
        jdbc.update("INSERT INTO products(id,user_id,sku_barcode,sku_name) VALUES(40,1,'other-anchor','다른 상품')");
        jdbc.update("UPDATE calculation_results SET product_id=40 WHERE id=812");
        rejected("INSIGHT_INPUT_UNAVAILABLE",()->generate(1921));unspent();
    }

}
