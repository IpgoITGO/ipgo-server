package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.service.ProductActivityService;
import com.ipgo.server.support.UploadTestDatabase;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ProductActivityIntegrationTests extends DatabaseIntegrationTest {
    static final LocalDate END = LocalDate.of(2026, 10, 31);
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRepository products;
    @Autowired UploadFileRepository uploads;
    @Autowired ProductActivityService activity;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    long owner, warehouse, stale, recent, manualOn, manualOff, legacyOn, legacyOff, unseen;
    UploadFile current;

    @BeforeEach void fixture() {
        UploadTestDatabase.clear(jdbc);
        owner = UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        jdbc.update("INSERT INTO warehouses(user_id,name) VALUES(?,'test')", owner);
        warehouse = jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id=?", Long.class, owner);
        stale = product("stale", "AUTO", true);
        recent = product("recent", "AUTO", true);
        manualOn = product("manual-on", "MANUAL_ON", true);
        manualOff = product("manual-off", "MANUAL_OFF", false);
        legacyOn = product("legacy-on", "LEGACY", true);
        legacyOff = product("legacy-off", "LEGACY", false);
        unseen = product("unseen", "AUTO", true);
        var old = done("2026-09", 1);
        for (long id : List.of(stale, manualOn, legacyOn)) stock(old, id, LocalDate.of(2026,9,30));
        current = done("2026-10", 1);
        for (int offset=0; offset<28; offset++) stock(current, recent, END.minusDays(offset));
        stock(current, manualOff, END);
        stock(current, legacyOff, END);
    }
    @AfterEach void clear() { UploadTestDatabase.clear(jdbc); }

    @Test void completeWindowOnlyChangesAutoAndPreservesSettings() {
        jdbc.update("UPDATE products SET unit_cost=777 WHERE id=?", stale);
        jdbc.update("INSERT INTO replenishment_settings(product_id,lead_time_days,updated_at) VALUES(?,7,CURRENT_TIMESTAMP)", stale);
        evaluate();
        assertThat(active(stale)).isFalse();
        assertThat(reason(stale)).isEqualTo("NO_RECENT_RECORDS");
        assertThat(active(recent)).isTrue();
        assertThat(active(manualOn)).isTrue();
        assertThat(active(manualOff)).isFalse();
        assertThat(active(legacyOn)).isTrue();
        assertThat(active(legacyOff)).isFalse();
        assertThat(active(unseen)).isTrue();
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM products WHERE id=?", Long.class, stale)).isEqualTo(777);
        assertThat(jdbc.queryForObject("SELECT lead_time_days FROM replenishment_settings WHERE product_id=?", Integer.class, stale)).isEqualTo(7);
    }
    @Test void partialWindowHoldsMissingButPositiveEvidenceRestores() {
        jdbc.update("DELETE FROM product_daily_stocks WHERE upload_file_id=? AND stock_date=?",current.getId(),END.minusDays(10));
        jdbc.update("UPDATE products SET is_active=FALSE WHERE id=?",recent);
        evaluate();
        assertThat(active(stale)).isTrue();
        assertThat(active(recent)).isTrue();
        assertThat(reason(stale)).isNull();
    }
    @Test void inclusiveBoundaryAndReturnOfAutomaticallyInactiveProduct() {
        stock(current, stale, END.minusDays(28));
        evaluate();
        assertThat(active(stale)).isFalse();
        stock(current, stale, END.minusDays(27));
        evaluate();
        assertThat(active(stale)).isTrue();
        assertThat(reason(stale)).isNull();
    }
    @Test void windowAcrossTwoMonthsUsesActualCoveredDaysAndLatestAsOfDate() {
        var november = done("2026-11",1);
        jdbc.update("UPDATE upload_files SET period_end=? WHERE id=?", LocalDate.of(2026,11,10),november.getId());
        for(int day=1;day<=10;day++) stock(november,recent,LocalDate.of(2026,11,day));
        evaluate(); // 10/14~11/10 두 달의 28일 자료가 있다.
        assertThat(active(stale)).isFalse();
        jdbc.update("UPDATE products SET is_active=TRUE,auto_inactive_reason=NULL WHERE id=?",stale);
        jdbc.update("DELETE FROM product_daily_stocks WHERE upload_file_id=? AND stock_date=?",current.getId(),LocalDate.of(2026,10,14));
        evaluate(); // 단 하루라도 비면 이전 true를 유지한다.
        assertThat(active(stale)).isTrue();
    }

    @Test void invalidObservationAndFiveDayNewProductStayActive() {
        jdbc.update("UPDATE product_daily_stocks SET is_valid_observation=FALSE WHERE product_id=?",recent);
        for (int i=0;i<5;i++) stock(current,unseen,END.minusDays(i));
        evaluate();
        assertThat(active(recent)).isTrue();
        assertThat(active(unseen)).isTrue();
    }
    @Test void emptyAndProcessingAndFailedNewestSourcesHold() {
        var pending=uploads.saveAndFlush(new UploadFile(warehouse,"2026-11.xlsx",UUID.randomUUID().toString(),"a".repeat(64),1,new UploadPeriod(YearMonth.of(2026,11))));
        evaluate();
        assertThat(active(stale)).isTrue();
        jdbc.update("UPDATE upload_files SET status='FAILED' WHERE id=?",pending.getId());
        evaluate();
        assertThat(active(stale)).isTrue();
        jdbc.update("DELETE FROM upload_files WHERE id=?",pending.getId());
        jdbc.update("DELETE FROM product_daily_stocks");
        evaluate();
        assertThat(active(stale)).isTrue();
    }
    @Test void latestRevisionAndFutureAndOutOfOrderMonthsDoNotInventCoverage() {
        var replacement=done("2026-10",2); // 새 revision은 빈 자료: 이전 28일을 재사용하면 안 된다.
        evaluate();
        assertThat(active(stale)).isTrue();
        done("2026-08",1); // 과거 월을 늦게 추가해도 최신 기준은 10월이다.
        evaluate();
        assertThat(active(stale)).isTrue();
        done("2099-01",1);
        evaluate();
        assertThat(active(stale)).isTrue();
    }
    @Test void explicitSameValueAndAutomaticOptInHaveDistinctMeaning() throws Exception {
        patchProduct(stale,"{\"isActive\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("activationMode").value("MANUAL_ON"));
        evaluate();
        assertThat(active(stale)).isTrue();
        patchProduct(stale,"{\"activationMode\":\"AUTO\"}").andExpect(status().isOk())
                .andExpect(jsonPath("isActive").value(false))
                .andExpect(jsonPath("autoInactiveReason").value("NO_RECENT_RECORDS"));
        patchProduct(stale,"{\"isActive\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("activationMode").value("MANUAL_OFF"));
        stock(current,stale,END);
        evaluate();
        assertThat(active(stale)).isFalse();
    }
    @Test void legacyAutoOptInUnderInsufficientCoverageDoesNotInventInactiveReason() throws Exception {
        jdbc.update("DELETE FROM product_daily_stocks");
        patchProduct(legacyOff,"{\"activationMode\":\"AUTO\"}").andExpect(status().isOk())
                .andExpect(jsonPath("isActive").value(false))
                .andExpect(jsonPath("autoInactiveReason").doesNotExist());
    }
    @Test void nameOnlyPatchPreservesModeAndInvalidInputOrOtherUserCannotChangeIt() throws Exception {
        patchProduct(legacyOn,"{\"skuName\":\"changed\",\"unitCost\":777}").andExpect(status().isOk())
                .andExpect(jsonPath("activationMode").value("LEGACY"));
        for(String invalid:List.of("{\"activationMode\":\"AUTO\",\"isActive\":true}","{\"activationMode\":\"MANUAL_ON\"}","{\"activationMode\":\"other\"}"))
            patchProduct(stale,invalid).andExpect(status().isBadRequest());
        long other=UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        mvc.perform(patch("/api/products/"+stale).with(jwt().jwt(j->j.subject(""+other)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}")).andExpect(status().isForbidden());
        assertThat(active(stale)).isTrue();
    }
    @Test void defaultListAndRecoveryListHaveCorrectPagingAndUserScopedCount() throws Exception {
        evaluate();
        mvc.perform(get("/api/products").with(jwt().jwt(j->j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("total").value(6))
                .andExpect(jsonPath("autoInactiveCount").value(1));
        mvc.perform(get("/api/products").param("includeAutoInactive","true").param("query","stale")
                .param("size","1").with(jwt().jwt(j->j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("total").value(1))
                .andExpect(jsonPath("items[0].isActive").value(false));
        mvc.perform(get("/api/products").param("query","stale").with(jwt().jwt(j->j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("total").value(0));
    }
    @Test void failureAfterEvaluationRollsBackActivityAndTenantIsolationIsEnforced() {
        assertThatThrownBy(()->tx.executeWithoutResult(status->{activity.evaluate(owner,warehouse,products.findByUserId(owner));throw new IllegalStateException("rollback");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(active(stale)).isTrue();
        long other=UploadTestDatabase.newUser(jdbc, UUID.randomUUID()+"@example.test");
        assertThatThrownBy(()->tx.executeWithoutResult(status->activity.evaluate(other,warehouse,List.of())))
                .isInstanceOf(com.ipgo.server.exception.ProductException.class);
    }

    @Test void manualCommitWinsWhileAutomaticEvaluationWaitsForWarehouseLock() throws Exception {
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var attempting = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var manual = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM warehouses WHERE id=? FOR UPDATE", Long.class, warehouse);
                var product=products.findById(stale).orElseThrow();
                product.update(null,null,true);
                products.flush();
                locked.countDown();
                try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("manual timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var automatic = executor.submit(() -> tx.executeWithoutResult(status -> {
                attempting.countDown();
                // UploadCleaningService와 동일하게 상품을 읽기 전에 창고부터 잠근다.
                jdbc.queryForObject("SELECT id FROM warehouses WHERE id=? FOR UPDATE", Long.class, warehouse);
                activity.evaluate(owner,warehouse,products.findByUserId(owner));
            }));
            assertThat(attempting.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            manual.get(15,java.util.concurrent.TimeUnit.SECONDS);
            automatic.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(active(stale)).isTrue();
            assertThat(jdbc.queryForObject("SELECT activation_mode FROM products WHERE id=?",String.class,stale)).isEqualTo("MANUAL_ON");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    void evaluate() { tx.executeWithoutResult(status->activity.evaluate(owner,warehouse,products.findByUserId(owner))); }
    boolean active(long id) { return jdbc.queryForObject("SELECT is_active FROM products WHERE id=?",Boolean.class,id); }
    String reason(long id) { return jdbc.queryForObject("SELECT auto_inactive_reason FROM products WHERE id=?",String.class,id); }
    long product(String barcode,String mode,boolean active) {
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name,activation_mode,is_active) VALUES(?,?,?,?,?)",owner,barcode,barcode,mode,active);
        return jdbc.queryForObject("SELECT id FROM products WHERE user_id=? AND sku_barcode=?",Long.class,owner,barcode);
    }
    UploadFile done(String month,int revision) {
        var u=new UploadFile(warehouse,month+".xlsx",UUID.randomUUID().toString(),"a".repeat(64),revision,new UploadPeriod(YearMonth.parse(month)));
        u.complete(u.getPeriodEnd(),new UploadProcessingReport(u.getPeriodEnd(),new UploadProcessingReport.Validation(1,1,List.of()),null,null));
        return uploads.saveAndFlush(u);
    }
    void stock(UploadFile upload,long product,LocalDate day) {
        jdbc.update("INSERT INTO product_daily_stocks(product_id,upload_file_id,stock_date,opening_qty,inbound_qty,outbound_qty,adjustment_qty,closing_qty,is_valid_observation) VALUES(?,?,?,5,0,0,0,5,TRUE)",product,upload.getId(),day);
    }
    org.springframework.test.web.servlet.ResultActions patchProduct(long id,String body) throws Exception {
        return mvc.perform(patch("/api/products/"+id).with(jwt().jwt(j->j.subject(""+owner))).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
