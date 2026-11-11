package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.service.WarehouseService;
import com.ipgo.server.support.UploadTestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP transport + Spring auth/controller/transaction + H2; upstream numbers here are synthetic. */
@AutoConfigureMockMvc
class CustomScenarioIntegrationTests extends DatabaseIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpServer HTTP;
    static final java.util.concurrent.ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicReference<JsonNode> SEEN = new AtomicReference<>();
    static volatile UnaryOperator<ObjectNode> modify = x -> x;
    static volatile String raw;
    static volatile int statusCode = 200;
    static volatile long blockedQty = -1;
    static volatile boolean slowBody;
    static volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    static {
        try {
            HTTP = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            HTTP.setExecutor(HTTP_THREADS);
            HTTP.createContext("/engine/scenarios", exchange -> {
                try {
                    JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    SEEN.set(request); CALLS.incrementAndGet();
                    long qty = request.path("qty").asLong();
                    int status = statusCode;
                    var edit = modify; String override = raw; boolean delayBody = slowBody;
                    var arrived = entered; var resume = release;
                    if (qty == blockedQty) { arrived.countDown(); resume.await(3,TimeUnit.SECONDS); }
                    var body = JSON.createObjectNode().put("type","CUSTOM").put("qty",qty).put("isDuplicate",false)
                            .putNull("expectedDepletionOn").put("expectedEndingStock",16.9)
                            .put("unmetDemandQty",2.3).put("postArrivalRisk",0.2);
                    body.set("dailyProjection", JSON.valueToTree(List.of(Map.of("date",
                            LocalDate.parse(request.path("calculationInput").path("today").asText()).plusDays(1).toString(),
                            "closingQty",16.9))));
                    byte[] bytes = (override == null ? JSON.writeValueAsString(edit.apply(body)) : override)
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(status,bytes.length);
                    if (delayBody) Thread.sleep(1600);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception ignored) { /* cancellation deliberately closes the peer */ }
                finally { exchange.close(); }
            });
            HTTP.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry p) {
        p.add("ipgo.engine.enabled",() -> "true");
        p.add("ipgo.engine.transport-enabled",() -> "false");
        p.add("ipgo.engine.base-url",() -> "http://127.0.0.1:"+HTTP.getAddress().getPort());
        p.add("ipgo.engine.scenario-timeout",() -> "1s");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WarehouseService warehouses;
    @Autowired TransactionTemplate tx;
    long owner,other,warehouse,product,upload,run,result;
    LocalDate today,asOf;
    JsonNode snapshot;

    @BeforeEach void setup() {
        UploadTestDatabase.clear(jdbc);
        CALLS.set(0); SEEN.set(null); modify=x -> x; raw=null; statusCode=200; blockedQty=-1; slowBody=false;
        entered=new CountDownLatch(1);release=new CountDownLatch(1);
        today=LocalDate.now(ZoneId.of("Asia/Seoul"));asOf=today.minusDays(2);
        owner=UploadTestDatabase.newUser(jdbc,"custom-"+UUID.randomUUID()+"@example.test");
        other=UploadTestDatabase.newUser(jdbc,"custom-"+UUID.randomUUID()+"@example.test");
        warehouse=warehouses.lockDefault(owner).getId();
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name,unit_cost) VALUES (?,'custom-test','상품',100)",owner);
        product=jdbc.queryForObject("SELECT MAX(id) FROM products",Long.class);
        jdbc.update("""
                INSERT INTO upload_files(warehouse_id,original_name,storage_key,file_hash,revision_no,period_start,period_end,status)
                VALUES (?,'sample.xlsx','test-only',?,1,?,?,'DONE')
                """,warehouse,"0".repeat(64),asOf.withDayOfMonth(1),asOf);
        upload=jdbc.queryForObject("SELECT MAX(id) FROM upload_files",Long.class);
        run=newRun("DONE");
        Map<String,Object> input=Map.of("runId",run,"userId",owner,"warehouseId",warehouse,
                "uploadFileIds",List.of(upload),"asOfDate",asOf.toString(),"today",today.toString(),
                "policy",Map.of("defaultMethod","B","multiplier",1.0),"settings",List.of(
                        Map.of("productId",product,"leadTimeDays",7,"reviewCycleDays",7,"orderUnitQty",12,"unitCost",100),
                        Map.of("productId",product+100,"leadTimeDays",5,"reviewCycleDays",8,"orderUnitQty",1,"unitCost",10)),
                "plannedReceipts",List.of(Map.of("productId",product,"quantity",5,"expectedOn",today.plusDays(3).toString())),
                "noDateReceipts",List.of(Map.of("productId",product,"quantity",5)));
        snapshot=JSON.readTree(JSON.writeValueAsBytes(Map.of("request",input)));
        jdbc.update("UPDATE calculation_runs SET selection_snapshot=CAST(? AS JSONB) WHERE id=?",JSON.writeValueAsString(snapshot),run);
        jdbc.update("INSERT INTO run_source_files(run_id,upload_file_id) VALUES (?,?)",run,upload);
        jdbc.update("""
                INSERT INTO calculation_results(run_id,product_id,origin_date,start_date,multiplier,horizon_days,
                status,input_snapshot,recommended_qty,pre_arrival_unmet_qty,pre_arrival_risk,accuracy_grade)
                VALUES (?,?,?,?,1,14,'OK',JSON '{}',72,2.1,0.04,'HIGH')
                """,run,product,asOf,today);
        result=jdbc.queryForObject("SELECT MAX(id) FROM calculation_results",Long.class);
        for (String type:List.of("P80","P100","P120")) {
            long qty=switch(type){case "P80"->60;case "P100"->72;default->96;};
            jdbc.update("""
                    INSERT INTO scenario_results(result_id,scenario_type,scenario_qty,expected_ending_stock,unmet_demand_qty,daily_projection)
                    VALUES (?,?,?,0,2.1,JSON '[]')
                    """,result,type,qty);
        }
        jdbc.update("""
                INSERT INTO ai_explanations(result_id,mode,status,request_payload,validation_passed,versions,rendered_text)
                VALUES (?,'NONE','DISABLED',JSON '{}',TRUE,JSON '{}','기존 설명')
                """,result);
    }
    @AfterEach void cleanup() { release.countDown(); UploadTestDatabase.clear(jdbc); }
    @AfterAll static void stop() { HTTP.stop(0);HTTP_THREADS.shutdownNow(); }

    @Test void roundsFromSnapshotAndPersistsOnlyCustomWithFullRiskContext() throws Exception {
        create(owner,13).andExpect(status().isCreated()).andExpect(jsonPath("type").value("CUSTOM"))
                .andExpect(jsonPath("qty").value(24)).andExpect(jsonPath("expectedEndingStock").value(16))
                .andExpect(jsonPath("unmetDemandQty").value(3)).andExpect(jsonPath("postArrivalUnmetQty").value(1));
        JsonNode sent=SEEN.get();assertThat(sent.path("resultId").asLong()).isEqualTo(result);
        assertThat(sent.path("calculationInput").path("settings").size()).isEqualTo(2);
        assertThat(sent.path("calculationInput").path("productIds")).isEqualTo(JSON.readTree("["+product+"]"));
        var expected=(ObjectNode)snapshot.path("request").deepCopy();expected.set("productIds",JSON.readTree("["+product+"]"));
        assertThat(sent.path("calculationInput")).isEqualTo(expected);
        assertBasePreserved();
        mvc.perform(get(path()).with(jwt().jwt(c->c.subject(""+owner)))).andExpect(status().isOk())
                .andExpect(jsonPath("scenarios.length()").value(4)).andExpect(jsonPath("scenarios[3].qty").value(24));
    }
    @Test void sameRoundedQuantityIsCachedAndReplacementKeepsOneCustom() throws Exception {
        long first=createdId(61); // 72: same as P100; server derives duplicate from stored base rows.
        assertThat(jdbc.queryForObject("SELECT is_duplicate FROM scenario_results WHERE id=?",Boolean.class,first)).isTrue();
        assertThat(createdId(62)).isEqualTo(first);assertThat(CALLS.get()).isEqualTo(1);
        assertThat(createdId(80)).isNotEqualTo(first);assertThat(CALLS.get()).isEqualTo(2);
        assertThat(customQty()).isEqualTo(84);assertThat(customCount()).isEqualTo(1);assertBasePreserved();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"qty\":null}","{\"qty\":0}","{\"qty\":-1}",
            "{\"qty\":1.5}","{\"qty\":\"12\"}","{\"qty\":true}","{\"qty\":9223372036854775807}",
            "{\"qty\":9223372036854775808}"})
    void rejectsInvalidPublicQuantitiesWithoutCallingAi(String body) throws Exception {
        postBody(owner,body).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_INPUT"));
        assertThat(CALLS.get()).isZero();assertThat(customCount()).isZero();
    }
    @Test void checksOwnershipBeforeSnapshotOrAiAccess() throws Exception {
        jdbc.update("UPDATE calculation_runs SET selection_snapshot=JSON '{}' WHERE id=?",run);
        create(other,13).andExpect(status().isForbidden());
        mvc.perform(post(path()).contentType("application/json").content("{\"qty\":13}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/results/9999999/scenarios").with(jwt().jwt(c->c.subject(""+owner)))
                .contentType("application/json").content("{\"qty\":13}")).andExpect(status().isNotFound());
        assertThat(CALLS.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"NOT_CALCULABLE","EXCLUDED","REUPLOAD_REQUIRED"})
    void rejectsIneligibleResults(String status) throws Exception {
        jdbc.update("UPDATE calculation_results SET status=? WHERE id=?",status,result);
        create(owner,13).andExpect(status().isConflict());assertThat(CALLS.get()).isZero();
    }
    @Test void allowsZeroRecommendationButNeverInventsRisk() throws Exception {
        jdbc.update("UPDATE calculation_results SET status='NOT_NEEDED',recommended_qty=0,pre_arrival_risk=NULL WHERE id=?",result);
        jdbc.update("DELETE FROM scenario_results WHERE result_id=?",result);
        modify=b->b.putNull("postArrivalRisk");
        create(owner,13).andExpect(status().isCreated()).andExpect(jsonPath("postArrivalRisk").doesNotExist());
    }
    @Test void rejectsMissingSnapshotOldRunAndInactiveProduct() throws Exception {
        jdbc.update("UPDATE calculation_runs SET selection_snapshot=JSON '{}' WHERE id=?",run);
        create(owner,13).andExpect(status().isConflict());
        jdbc.update("UPDATE calculation_runs SET selection_snapshot=CAST(? AS JSONB) WHERE id=?",JSON.writeValueAsString(snapshot),run);
        jdbc.update("UPDATE products SET is_active=FALSE WHERE id=?",product);
        create(owner,13).andExpect(status().isConflict());jdbc.update("UPDATE products SET is_active=TRUE WHERE id=?",product);
        newRun("DONE");create(owner,13).andExpect(status().isConflict());assertThat(CALLS.get()).isZero();
    }
    @Test void laterFailedRunKeepsShownResultButPendingRunStillBlocks() throws Exception {
        // #174: 화면은 마지막 DONE 결과를 보여 주므로 뒤에 실패한 계산만 있으면 그 결과로 비교할 수 있다.
        newRun("FAILED");newRun("FAILED");
        create(owner,13).andExpect(status().isCreated()).andExpect(jsonPath("qty").value(24));
        assertThat(CALLS.get()).isEqualTo(1);assertBasePreserved();
        for (String pending:List.of("QUEUED","RUNNING")) {
            long next=newRun(pending);
            create(owner,37).andExpect(status().isConflict()).andExpect(jsonPath("code").value("SCENARIO_STALE"));
            jdbc.update("UPDATE calculation_runs SET status='FAILED' WHERE id=?",next);
        }
        assertThat(CALLS.get()).isEqualTo(1);assertThat(customQty()).isEqualTo(24);
    }
    @Test void rejectsExpiredSourceAndReplacedRevision() throws Exception {
        jdbc.update("UPDATE calculation_runs SET as_of_date=? WHERE id=?",today.minusDays(8),run);
        create(owner,13).andExpect(status().isConflict());jdbc.update("UPDATE calculation_runs SET as_of_date=? WHERE id=?",asOf,run);
        jdbc.update("""
                INSERT INTO upload_files(warehouse_id,original_name,storage_key,file_hash,revision_no,period_start,period_end,status)
                VALUES (?,'new.xlsx','new',?,2,?,?,'PROCESSING')
                """,warehouse,"1".repeat(64),asOf.withDayOfMonth(1),asOf);
        create(owner,13).andExpect(status().isConflict());assertThat(CALLS.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"wrong-qty","wrong-type","bad-number","bad-risk","bad-projection","bad-json","too-large"})
    void badAiResponsePreservesPreviousCustomAndBase(String kind) throws Exception {
        createdId(13);long previous=customQty();
        switch(kind){
            case "wrong-qty"->modify=b->b.put("qty",1);
            case "wrong-type"->modify=b->b.put("type","P100");
            case "bad-number"->modify=b->b.put("expectedEndingStock",-1);
            case "bad-risk"->modify=b->b.put("postArrivalRisk",2);
            case "bad-projection"->modify=b->{b.set("dailyProjection",JSON.valueToTree(List.of(Map.of("date",today.plusDays(99).toString(),"closingQty",0))));return b;};
            case "bad-json"->raw="not-json";
            case "too-large"->raw="{\"data\":\""+"a".repeat(600000)+"\"}";
        }
        create(owner,80).andExpect(status().isBadGateway()).andExpect(jsonPath("code").value("ENGINE_ERROR"));
        assertThat(customQty()).isEqualTo(previous);assertBasePreserved();
    }
    @Test void httpFailureBusyAndBodyTimeoutPreserveStoredResult() throws Exception {
        createdId(13);
        statusCode=500;raw="sensitive-upstream-error";
        create(owner,80).andExpect(status().isBadGateway()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
        statusCode=503;create(owner,80).andExpect(status().isServiceUnavailable());
        statusCode=200;raw=null;slowBody=true;
        create(owner,80).andExpect(status().isGatewayTimeout()).andExpect(jsonPath("code").value("ENGINE_TIMEOUT"));
        assertThat(customQty()).isEqualTo(24);assertBasePreserved();
    }
    @Test void databaseFailureRollsBackReplacement() throws Exception {
        createdId(13);jdbc.execute("ALTER TABLE scenario_results ADD CONSTRAINT reject_custom_test CHECK (scenario_type <> 'CUSTOM' OR scenario_qty <> 84)");
        try { create(owner,80).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("SCENARIO_SAVE_FAILED")); }
        finally { jdbc.execute("ALTER TABLE scenario_results DROP CONSTRAINT reject_custom_test"); }
        assertThat(customQty()).isEqualTo(24);assertThat(customCount()).isEqualTo(1);assertBasePreserved();
    }
    @Test void differentConcurrentRequestCannotOverwriteNewerSuccess() throws Exception {
        blockedQty=24;var first=asyncCreate(13);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        create(owner,37).andExpect(status().isCreated());release.countDown();
        assertThat(first.get(3,TimeUnit.SECONDS)).isEqualTo(409);assertThat(customQty()).isEqualTo(48);
        assertThat(customCount()).isEqualTo(1);assertBasePreserved();
    }
    @Test void sameConcurrentQuantityReturnsTheSameStoredRow() throws Exception {
        blockedQty=24;var first=asyncCreate(13);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        blockedQty=-1;createdId(14);release.countDown();assertThat(first.get(3,TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(customCount()).isEqualTo(1);assertThat(customQty()).isEqualTo(24);
    }
    @Test void calculationCanChangeDuringHttpAndStaleAnswerIsDiscarded() throws Exception {
        blockedQty=24;var first=asyncCreate(13);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        tx.executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM warehouses WHERE id=? FOR UPDATE",Long.class,warehouse);newRun("QUEUED");});
        release.countDown();assertThat(first.get(3,TimeUnit.SECONDS)).isEqualTo(409);assertThat(customCount()).isZero();
    }
    @Test void publicBodyCannotOverrideTheStoredOwnerOrSource() throws Exception {
        postBody(owner,"{\"qty\":13,\"calculationInput\":{\"userId\":"+other+",\"uploadFileIds\":[9999]}}")
                .andExpect(status().isCreated());
        assertThat(SEEN.get().path("calculationInput").path("userId").asLong()).isEqualTo(owner);
        assertThat(SEEN.get().path("calculationInput").path("uploadFileIds").get(0).asLong()).isEqualTo(upload);
    }
    @ParameterizedTest @ValueSource(strings={"owner","warehouse","run","unselected","missing-unit","negative-cost","foreign-source"})
    void rejectsDamagedOrUnrelatedOriginalInputsBeforeHttp(String kind) throws Exception {
        var damaged=(ObjectNode)snapshot.deepCopy();var request=(ObjectNode)damaged.path("request");
        switch(kind){
            case "owner"->request.put("userId",other);
            case "warehouse"->request.put("warehouseId",999999);
            case "run"->request.put("runId",run+1);
            case "unselected"->request.set("productIds",JSON.valueToTree(List.of(product+100)));
            case "missing-unit"->((ObjectNode)request.path("settings").get(0)).remove("orderUnitQty");
            case "negative-cost"->((ObjectNode)request.path("settings").get(0)).put("unitCost",-1);
            case "foreign-source"->request.set("uploadFileIds",JSON.valueToTree(List.of(upload+9999)));
        }
        jdbc.update("UPDATE calculation_runs SET selection_snapshot=CAST(? AS JSONB) WHERE id=?",JSON.writeValueAsString(damaged),run);
        create(owner,13).andExpect(status().isConflict());assertThat(CALLS.get()).isZero();
    }
    private CompletableFuture<Integer> asyncCreate(long qty) {
        return CompletableFuture.supplyAsync(()->{try{return create(owner,qty).andReturn().getResponse().getStatus();}catch(Exception e){throw new RuntimeException(e);}});
    }
    private long createdId(long qty) throws Exception {
        var response=create(owner,qty).andExpect(status().isCreated()).andReturn().getResponse();
        return JSON.readTree(response.getContentAsString()).path("scenarioId").asLong();
    }
    private ResultActions create(long user,long qty) throws Exception {return postBody(user,"{\"qty\":"+qty+"}");}
    private ResultActions postBody(long user,String body) throws Exception {return mvc.perform(post(path()).with(jwt().jwt(c->c.subject(""+user))).contentType("application/json").content(body));}
    private String path(){return "/api/results/"+result+"/scenarios";}
    private long customQty(){return jdbc.queryForObject("SELECT scenario_qty FROM scenario_results WHERE result_id=? AND scenario_type='CUSTOM'",Long.class,result);}
    private int customCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM scenario_results WHERE result_id=? AND scenario_type='CUSTOM'",Integer.class,result);}
    private long newRun(String status){jdbc.update("""
            INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at)
            VALUES (?,'CALCULATE',?,?,'test',JSON '{}',CURRENT_TIMESTAMP)
            """,warehouse,asOf,status);return jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs",Long.class);}
    private void assertBasePreserved(){
        assertThat(jdbc.queryForObject("SELECT recommended_qty FROM calculation_results WHERE id=?",Long.class,result)).isEqualTo(72);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scenario_results WHERE result_id=? AND scenario_type<>'CUSTOM'",Integer.class,result)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT rendered_text FROM ai_explanations WHERE result_id=?",String.class,result)).isEqualTo("기존 설명");
        JsonNode stored=JSON.readTree(jdbc.queryForObject("SELECT selection_snapshot FROM calculation_runs WHERE id=?",String.class,run));
        if(stored.isTextual())stored=JSON.readTree(stored.asText());assertThat(stored).isEqualTo(snapshot);
    }
}
