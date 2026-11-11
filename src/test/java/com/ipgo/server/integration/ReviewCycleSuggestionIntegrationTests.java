package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.dto.request.DefaultSettingsRequest;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.service.ReplenishmentService;
import com.ipgo.server.support.UploadTestDatabase;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ReviewCycleSuggestionIntegrationTests extends DatabaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UploadFileRepository uploads;
    @Autowired ReplenishmentService replenishment;
    @Autowired com.ipgo.server.service.UploadCleaningService cleaning;
    @Autowired tools.jackson.databind.json.JsonMapper mapper;
    long owner, warehouse, product;

    @BeforeEach void fixture() {
        UploadTestDatabase.clear(jdbc);
        owner = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        jdbc.update("INSERT INTO warehouses(user_id,name) VALUES (?,'기본 창고')", owner);
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id = ?", Long.class, owner);
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name) VALUES (?,'A','상품A')", owner);
        product = jdbc.queryForObject("SELECT id FROM products WHERE user_id = ?", Long.class, owner);
    }
    @AfterEach void cleanup() { UploadTestDatabase.clear(jdbc); }

    @Test void inboundDaysAcrossMonthsPopulateS1P1K1WithoutWritesOrCalculation() throws Exception {
        // #166: 한 달에 1~2번 들어와도 여러 달을 합쳐 3번 이상이면 추정한다. 9/1 · 9/8 · 10/6 → 간격 7 · 28 → 중앙값 17.5 → 18
        done(warehouse,"2026-10",1,6);
        done(warehouse,"2026-09",1,1,8); // 오래된 자료를 나중에 올려도 기간 순서와 관계없이 합친다
        s1(18,"ESTIMATED",false);
        mvc.perform(get("/api/replenishment-settings").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(jsonPath("reviewCycleBasis.from").value("2025-11-01"))
                .andExpect(jsonPath("reviewCycleBasis.to").value("2026-10-31"))
                .andExpect(jsonPath("reviewCycleBasis.productCount").value(1));
        mvc.perform(get("/api/products").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("items[0].settings.reviewCycleDays").value(18))
                .andExpect(jsonPath("items[0].overridden").isEmpty());
        mvc.perform(get("/api/products/{id}/detail",product).with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("settings.reviewCycleDays").value(18))
                .andExpect(jsonPath("settings.reviewCycleSource").value("ESTIMATED"));
        assertThat(jdbc.queryForObject("SELECT default_review_cycle_days FROM warehouses WHERE id = ?",Integer.class,warehouse)).isNull();
        for (String table : List.of("replenishment_settings","calculation_runs"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
    }
    @Test void fewerThanThreeInboundDaysOrOlderThanTwelveMonthsGivesNoEstimate() throws Exception {
        done(warehouse,"2025-10",1,1,8,15); // 마지막 날(2026-10-31)에서 12개월보다 앞이라 빠진다
        done(warehouse,"2026-10",1,1,20);
        s1(null,"ESTIMATED",false);
        mvc.perform(get("/api/replenishment-settings").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(jsonPath("reviewCycleBasis").isEmpty());
    }
    @Test void medianAcrossProductsIsRoundedLikeTheEngine() throws Exception {
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name) VALUES (?,'B','상품B')", owner);
        long second = jdbc.queryForObject("SELECT id FROM products WHERE user_id = ? AND sku_barcode = 'B'", Long.class, owner);
        var upload = pending(warehouse,"2026-10",1);
        finish(upload,null,1,8,15);                  // A: 7
        inbound(upload,second,1,11,21,30);           // B: 10 · 10 · 9 → 10
        s1(9,"ESTIMATED",false);                     // (7 + 10) / 2 = 8.5 → 9
        mvc.perform(get("/api/replenishment-settings").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(jsonPath("reviewCycleBasis.productCount").value(2));
    }
    @Test void excludedProductOfAFileIsNotCounted() throws Exception {
        var upload = pending(warehouse,"2026-10",1);
        inbound(upload,product,1,8,15);
        upload.complete(upload.getPeriodEnd(), new UploadProcessingReport(upload.getPeriodEnd(),
                new UploadProcessingReport.Validation(1,0,List.of(new UploadProcessingReport.ExcludedSku(product,"A","상품A",
                        LocalDate.of(2026,10,3),"검산 불일치"))),null,null));
        uploads.saveAndFlush(upload);
        s1(null,"ESTIMATED",false);
    }
    @Test void higherRevisionWinsEvenWhenOlderRevisionCompletesLater() throws Exception {
        done(warehouse,"2026-10",2,1,10,19); done(warehouse,"2026-10",1,1,8,15);
        s1(9,"ESTIMATED",false);
    }
    @Test void processingRevisionDoesNotUseItsSupersededReportButCanUseOtherCompletedPeriod() throws Exception {
        done(warehouse,"2026-09",1,1,15,29); done(warehouse,"2026-10",1,1,8,15);
        pending(warehouse,"2026-10",2); s1(14,"ESTIMATED",false);
    }
    @Test void failedRevisionDoesNotUseItsSupersededReport() throws Exception {
        done(warehouse,"2026-10",1,1,8,15);
        var failed = pending(warehouse,"2026-10",2);
        failed.fail(new UploadProcessingReport.Failure("INVALID_SOURCE","시험 실패")); uploads.saveAndFlush(failed);
        s1(null,"ESTIMATED",false);
    }
    @Test void anotherWarehouseOrUserCannotProvideSuggestion() throws Exception {
        long other=UploadTestDatabase.newUser(jdbc,UUID.randomUUID()+"@example.test");
        jdbc.update("INSERT INTO warehouses(user_id,name) VALUES (?,'다른 창고')",other);
        long otherWarehouse=jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id = ?",Long.class,other);
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name) VALUES (?,'A','상품A')", other);
        long otherProduct=jdbc.queryForObject("SELECT id FROM products WHERE user_id = ?",Long.class,other);
        var upload=pending(otherWarehouse,"2026-10",1);
        inbound(upload,otherProduct,1,9,17);
        upload.complete(upload.getPeriodEnd(),new UploadProcessingReport(upload.getPeriodEnd(),new UploadProcessingReport.Validation(1,1,List.of()),8,null));
        uploads.saveAndFlush(upload);
        s1(null,"ESTIMATED",false);
    }
    @Test void confirmedDefaultsAndProductOverridesSurviveNewReports() throws Exception {
        replenishment.saveDefaults(owner,new DefaultSettingsRequest(5,11,1L,false));
        jdbc.update("INSERT INTO replenishment_settings(product_id,lead_time_days,review_cycle_days,order_unit_qty,updated_at) VALUES (?,NULL,19,NULL,CURRENT_TIMESTAMP)",product);
        done(warehouse,"2026-10",1,1,8,15); s1(11,"DEFAULT",true);
        mvc.perform(get("/api/products").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(jsonPath("items[0].settings.reviewCycleDays").value(19));
        mvc.perform(get("/api/products/{id}/detail",product).with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(jsonPath("settings.reviewCycleDays").value(19))
                .andExpect(jsonPath("settings.reviewCycleSource").value("PRODUCT"));
    }
    @Test void simultaneousReportPersistenceAndUserConfirmationKeepConfirmedValue() throws Exception {
        var upload=pending(warehouse,"2026-10",1);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var report=pool.submit(() -> { start.await(); finish(upload,7); return true; });
            var confirm=pool.submit(() -> { start.await(); replenishment.saveDefaults(owner,new DefaultSettingsRequest(5,11,1L,false)); return true; });
            start.countDown(); assertThat(report.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(confirm.get(20,TimeUnit.SECONDS)).isTrue();
        }
        s1(11,"DEFAULT",true);
        assertThat(uploads.findById(upload.getId()).orElseThrow().getValidationReport().estimatedReviewCycleDays()).isEqualTo(7);
    }
    @Test void actualAiXlsxContractPersistsAndIsVisibleWithoutConfirmingSettings() throws Exception {
        var upload=pending(warehouse,"2026-05",1);
        var json=mapper.readTree(getClass().getResourceAsStream("/contracts/review-cycle-clean-result.json"));
        for (String field : List.of("unitDailyRecords","productDailyStocks"))
            for (var row : json.get(field)) ((tools.jackson.databind.node.ObjectNode)row).put("uploadFileId",upload.getId());
        var result=mapper.treeToValue(json,com.ipgo.server.dto.CleaningResult.class);
        assertThat(cleaning.complete(upload.getId(),upload.getJobId(),result))
                .isEqualTo(com.ipgo.server.service.UploadCleaningService.Outcome.APPLIED);
        s1(7,"ESTIMATED",false);
        assertThat(uploads.findById(upload.getId()).orElseThrow().getValidationReport().estimatedReviewCycleDays()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT default_review_cycle_days FROM warehouses WHERE id = ?",Integer.class,warehouse)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calculation_runs",Long.class)).isZero();
    }
    private void s1(Integer cycle,String source,boolean confirmed) throws Exception {
        mvc.perform(get("/api/replenishment-settings").with(jwt().jwt(j -> j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("reviewCycleDays").value(cycle))
                .andExpect(jsonPath("reviewCycleSource").value(source)).andExpect(jsonPath("confirmed").value(confirmed));
    }
    private UploadFile pending(long w,String month,int rev) {
        return uploads.saveAndFlush(new UploadFile(w,month+".xlsx",UUID.randomUUID().toString(),"a".repeat(64),rev,new UploadPeriod(YearMonth.parse(month))));
    }
    private void done(long w,String month,int rev,int... inboundDays) { finish(pending(w,month,rev),null,inboundDays); }
    // 업로드를 정제 완료로 바꾸고, 그 달의 inboundDays 날짜에 상품 A의 입고 기록을 남긴다.
    private void finish(UploadFile upload,Integer reportCycle,int... inboundDays) {
        inbound(upload,product,inboundDays);
        LocalDate end=upload.getPeriodEnd();
        // 실제 정제 결과처럼 그 달 마지막 날 기록도 남긴다(입고 0). 12개월 기간의 끝을 정한다.
        if (java.util.Arrays.stream(inboundDays).noneMatch(d -> d == end.getDayOfMonth())) jdbc.update("""
                INSERT INTO product_daily_stocks(product_id,upload_file_id,stock_date,opening_qty,inbound_qty,outbound_qty,
                    adjustment_qty,closing_qty,is_valid_observation) VALUES (?,?,?,5,0,0,0,5,TRUE)
                """,product,upload.getId(),end);
        upload.complete(end,new UploadProcessingReport(end,new UploadProcessingReport.Validation(1,1,List.of()),reportCycle,null));
        uploads.saveAndFlush(upload);
    }
    private void inbound(UploadFile upload,long productId,int... days) {
        for (int day : days) jdbc.update("""
                INSERT INTO product_daily_stocks(product_id,upload_file_id,stock_date,opening_qty,inbound_qty,outbound_qty,
                    adjustment_qty,closing_qty,is_valid_observation) VALUES (?,?,?,0,5,0,0,5,TRUE)
                """,productId,upload.getId(),upload.getPeriodStart().withDayOfMonth(day));
    }
}
