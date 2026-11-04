package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.service.WarehouseService;
import com.ipgo.server.support.UploadTestDatabase;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in: actual FastAPI math + read-only PostgreSQL source views + Spring CUSTOM storage, synthetic 31-day SKU. */
@AutoConfigureMockMvc
@Import(CustomScenarioRealAiIntegrationTests.FrozenTime.class)
@EnabledIfEnvironmentVariable(named="IPGO_SCENARIO_REAL_AI_URL",matches=".+")
class CustomScenarioRealAiIntegrationTests extends DatabaseIntegrationTest {
    static final LocalDate AS_OF=LocalDate.of(2026,10,31), TODAY=LocalDate.of(2026,11,4);
    @TestConfiguration static class FrozenTime {
        @Bean @Primary Clock scenarioFixtureClock(){return Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"),ZoneOffset.UTC);}
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry p){
        String database=System.getenv("IPGO_TEST_DB_URL"),base=System.getenv("IPGO_SCENARIO_REAL_AI_URL");
        if(database==null||!database.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/scenario_test")
                ||!base.matches("http://127\\.0\\.0\\.1:[0-9]+"))
            throw new IllegalArgumentException("Real AI test requires isolated loopback scenario_test database and API");
        p.add("ipgo.engine.enabled",()->"true");p.add("ipgo.engine.transport-enabled",()->"false");
        p.add("ipgo.engine.base-url",()->base);p.add("ipgo.engine.scenario-timeout",()->"10s");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired WarehouseService warehouses;
    @Autowired JsonMapper mapper;

    @Test void realAiReadsTheOriginalSourceAndCustomReplacementPreservesBaseAndExplanation() throws Exception {
        // Extract only the existing read view definitions into the disposable test DB; not a production migration.
        for(String name:List.of("V13__create_ai_reader_view.sql","V14__create_engine_jobs_and_cleaning_views.sql")){
            try(var stream=getClass().getResourceAsStream("/db/migration/"+name)){
                String sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
                var definitions=Pattern.compile("(?s)CREATE VIEW (?:ai_product_daily_stocks_v1|ai_cleaning_sources_v1|ai_cleaning_products_v1|ai_cleaning_previous_units_v1)\\b.*?;").matcher(sql);
                while(definitions.find())jdbc.execute(definitions.group().replaceFirst("CREATE VIEW","CREATE OR REPLACE VIEW"));
            }
        }
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='server94_reader') THEN CREATE ROLE server94_reader LOGIN; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO server94_reader");
        jdbc.execute("GRANT SELECT ON ai_product_daily_stocks_v1,ai_cleaning_sources_v1,ai_cleaning_products_v1,ai_cleaning_previous_units_v1 TO server94_reader");
        UploadTestDatabase.clear(jdbc);
        try {
            long user=UploadTestDatabase.newUser(jdbc,"real-scenario-"+UUID.randomUUID()+"@example.test");
            long warehouse=warehouses.lockDefault(user).getId();
            jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name,unit_cost) VALUES (?,'real-ai-fixture','합성31일상품',100)",user);
            long product=jdbc.queryForObject("SELECT MAX(id) FROM products",Long.class);
            jdbc.update("""
                    INSERT INTO upload_files(warehouse_id,original_name,storage_key,file_hash,revision_no,period_start,period_end,status)
                    VALUES (?,'synthetic-2026-10.xlsx','synthetic-only',?,1,'2026-10-01','2026-10-31','DONE')
                    """,warehouse,"0".repeat(64));
            long upload=jdbc.queryForObject("SELECT MAX(id) FROM upload_files",Long.class);
            for(int day=1;day<=31;day++)jdbc.update("""
                    INSERT INTO product_daily_stocks(product_id,upload_file_id,stock_date,opening_qty,inbound_qty,
                    outbound_qty,adjustment_qty,closing_qty,is_valid_observation) VALUES (?,?,?,?,0,2,0,?,TRUE)
                    """,product,upload,LocalDate.of(2026,10,day),70-2*(day-1),70-2*day);
            jdbc.update("""
                    INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,engine_version,selection_snapshot,created_at)
                    VALUES (?,'CALCULATE',?,'DONE','real-ai-test',JSON '{}',CURRENT_TIMESTAMP)
                    """,warehouse,AS_OF);
            long run=jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs",Long.class);
            Map<String,Object> input=new LinkedHashMap<>();
            input.put("runId",run);input.put("userId",user);input.put("warehouseId",warehouse);
            input.put("uploadFileIds",List.of(upload));input.put("productIds",List.of(product));
            input.put("asOfDate",AS_OF.toString());input.put("today",TODAY.toString());
            input.put("policy",Map.of("defaultMethod","B","multiplier",1.0));
            input.put("settings",List.of(Map.of("productId",product,"leadTimeDays",7,"reviewCycleDays",7,"orderUnitQty",12,"unitCost",100)));
            input.put("plannedReceipts",List.of(Map.of("productId",product,"quantity",5,"expectedOn",TODAY.plusDays(3).toString())));
            input.put("noDateReceipts",List.of(Map.of("productId",product,"quantity",5)));
            String snapshot=mapper.writeValueAsString(Map.of("request",input));
            jdbc.update("UPDATE calculation_runs SET selection_snapshot=CAST(? AS JSONB) WHERE id=?",snapshot,run);
            jdbc.update("INSERT INTO run_source_files(run_id,upload_file_id) VALUES (?,?)",run,upload);
            JsonNode baseline=engine("/engine/calculate",input).path("results").get(0);
            assertThat(baseline.path("status").asText()).isEqualTo("OK");
            jdbc.update("""
                    INSERT INTO calculation_results(run_id,product_id,origin_date,start_date,multiplier,horizon_days,
                    status,input_snapshot,recommended_qty,pre_arrival_unmet_qty,pre_arrival_risk,accuracy_grade)
                    VALUES (?,?,?,?,1,14,'OK',JSON '{}',?,?,?,?)
                    """,run,product,AS_OF,TODAY,baseline.path("recommendedQty").asLong(),
                    decimal(baseline.get("preArrivalUnmetQty")),decimal(baseline.get("preArrivalRisk")),baseline.path("accuracyGrade").asText());
            long result=jdbc.queryForObject("SELECT MAX(id) FROM calculation_results",Long.class);
            for(JsonNode s:baseline.path("scenarios"))jdbc.update("""
                    INSERT INTO scenario_results(result_id,scenario_type,scenario_qty,is_duplicate,expected_depletion_on,
                    expected_ending_stock,unmet_demand_qty,post_arrival_risk,daily_projection)
                    VALUES (?,?,?,?,?,?,?,?,CAST(? AS JSONB))
                    """,result,s.path("type").asText(),s.path("qty").asLong(),s.path("isDuplicate").asBoolean(),
                    s.path("expectedDepletionOn").isNull()?null:LocalDate.parse(s.path("expectedDepletionOn").asText()),
                    decimal(s.get("expectedEndingStock")),decimal(s.get("unmetDemandQty")),decimal(s.get("postArrivalRisk")),
                    mapper.writeValueAsString(s.path("dailyProjection")));
            jdbc.update("""
                    INSERT INTO ai_explanations(result_id,mode,status,request_payload,validation_passed,versions,reason_code)
                    VALUES (?,'NONE','DISABLED',JSON '{}',TRUE,JSON '{}','LLM_DISABLED')
                    """,result);
            var baseRows=jdbc.queryForList("SELECT * FROM scenario_results ORDER BY id");
            JsonNode expected=engine("/engine/scenarios",Map.of("resultId",result,"qty",24,"calculationInput",input));
            String path="/api/results/"+result+"/scenarios";
            var first=mvc.perform(post(path).with(jwt().jwt(c->c.subject(""+user))).contentType("application/json").content("{\"qty\":13}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("qty").value(24)).andExpect(jsonPath("type").value("CUSTOM")).andReturn().getResponse();
            JsonNode custom=mapper.readTree(first.getContentAsString());
            assertThat(custom.path("expectedEndingStock").asLong()).isEqualTo(decimal(expected.get("expectedEndingStock")).longValue());
            long firstId=custom.path("scenarioId").asLong();
            mvc.perform(post(path).with(jwt().jwt(c->c.subject(""+user))).contentType("application/json").content("{\"qty\":14}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("scenarioId").value(firstId));
            mvc.perform(post(path).with(jwt().jwt(c->c.subject(""+user))).contentType("application/json").content("{\"qty\":37}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("qty").value(48));
            mvc.perform(get(path).with(jwt().jwt(c->c.subject(""+user)))).andExpect(status().isOk()).andExpect(jsonPath("scenarios.length()").value(4));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scenario_results WHERE scenario_type='CUSTOM'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT * FROM scenario_results WHERE scenario_type<>'CUSTOM' ORDER BY id")).isEqualTo(baseRows);
            assertThat(jdbc.queryForObject("SELECT reason_code FROM ai_explanations WHERE result_id=?",String.class,result)).isEqualTo("LLM_DISABLED");
            assertThat(jdbc.queryForObject("SELECT selection_snapshot::text FROM calculation_runs WHERE id=?",String.class,run)).isEqualTo(jdbc.queryForObject("SELECT CAST(? AS JSONB)::text",String.class,snapshot));
        } finally {UploadTestDatabase.clear(jdbc);}
    }
    private JsonNode engine(String path,Object input) throws Exception {
        try(var http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()){
            var request=HttpRequest.newBuilder(URI.create(System.getenv("IPGO_SCENARIO_REAL_AI_URL")+path))
                    .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body());
        }
    }
    private static BigDecimal decimal(JsonNode n){return n==null||n.isNull()?null:n.decimalValue();}
}
