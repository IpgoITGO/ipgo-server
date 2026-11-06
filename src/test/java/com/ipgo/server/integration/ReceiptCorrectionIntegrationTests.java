package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.dto.request.ReceiptCorrectionRequest;
import com.ipgo.server.dto.response.ReceiptCorrection.*;
import com.ipgo.server.entity.*;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.*;
import com.ipgo.server.service.*;
import com.ipgo.server.support.UploadTestDatabase;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ReceiptCorrectionIntegrationTests extends DatabaseIntegrationTest {
    static final LocalDate DAY=LocalDate.of(2026,9,1);
    @TestConfiguration static class Time {
        @Bean @Primary Clock correctionClock() { return Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"),ZoneOffset.UTC); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadFileRepository uploads;
    @Autowired PlannedReceiptRepository receipts;
    @Autowired UploadCleaningService cleaning;
    @Autowired ReceiptMatchingService matching;
    @Autowired ReceiptCorrectionService corrections;
    @Autowired PlannedReceiptService orders;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired TransactionTemplate tx;
    @MockitoBean BillingService billing;
    long owner,warehouse,product,first,second,event;
    UploadFile original,changed;

    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("ipgo.engine.enabled",()->"true");
        r.add("ipgo.engine.transport-enabled",()->"false");
    }
    @BeforeEach void setup() {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)='engine_jobs'",Integer.class)==0)
            jdbc.execute("CREATE TABLE engine_jobs(job_id VARCHAR(100) PRIMARY KEY,job_type VARCHAR(20),target_id BIGINT,warehouse_id BIGINT,payload JSONB,status VARCHAR(20),attempts INTEGER DEFAULT 0,next_attempt_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,completed_at TIMESTAMP WITH TIME ZONE)");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name)='calculation_runs' AND LOWER(column_name)='input_hash'",Integer.class)==0)
            jdbc.execute("ALTER TABLE calculation_runs ADD COLUMN input_hash VARCHAR(64)");
        jdbc.update("DELETE FROM engine_jobs");UploadTestDatabase.clear(jdbc);
        owner=UploadTestDatabase.newUser(jdbc,UUID.randomUUID()+"@example.test");
        jdbc.update("INSERT INTO warehouses(user_id,name) VALUES (?,'정정 시험')",owner);
        warehouse=jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id=?",Long.class,owner);
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name,unit_cost) VALUES (?,'A','상품A',100)",owner);
        product=jdbc.queryForObject("SELECT id FROM products WHERE user_id=?",Long.class,owner);
        first=receipt(100);second=receipt(50);
        original=upload();complete(original,160,true);
        event=jdbc.queryForObject("SELECT id FROM receipt_matching_events WHERE warehouse_id=?",Long.class,warehouse);
        changed=upload();complete(changed,200,true);
    }
    @AfterEach void cleanup() { jdbc.update("DELETE FROM engine_jobs");UploadTestDatabase.clear(jdbc); }

    @Test void getAndPreviewAreReadOnlyAndKeepPreservesHistoryAndWarnings() throws Exception {
        String originalReport=mapper.writeValueAsString(matching.report(original.getId()));
        var before=corrections.detail(owner,event);long history=count("receipt_allocation_history");
        assertThat(before.currentState()).isEqualTo("OPEN");assertThat(before.currentSource().quantity()).isEqualTo(200);
        assertThat(before.originalSource().quantity()).isEqualTo(160);
        var preview=corrections.preview(owner,event,preview(before,Action.KEEP,List.of()));
        assertThat(preview.before()).isEqualTo(preview.after());assertThat(preview.mayRecalculate()).isFalse();
        assertThat(count("receipt_correction_decisions")).isZero();assertThat(count("calculation_runs")).isZero();
        assertThat(count("receipt_allocation_history")).isEqualTo(history);
        var saved=corrections.decide(owner,event,request(before,Action.KEEP,List.of(),UUID.randomUUID()));
        assertThat(saved.currentState()).isEqualTo("RESOLVED");assertThat(saved.runId()).isNull();
        var after=corrections.detail(owner,event);
        assertThat(after.allocations()).isEqualTo(before.allocations());assertThat(after.decisions()).hasSize(1);
        assertThat(after.lastDecision().decisionId()).isEqualTo(saved.decisionId());
        assertThat(after.version()).isEqualTo(saved.version());assertThat(after.version()).isNotEqualTo(before.version());
        assertThat(mapper.writeValueAsString(matching.report(original.getId()))).isEqualTo(originalReport);
        assertThat(count("receipt_allocation_history")).isEqualTo(history);
        mvc.perform(get("/api/receipt-corrections/{id}",event).with(jwt().jwt(j->j.subject(""+owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("currentState").value("RESOLVED"));
        verify(billing).assertWriteAllowed(owner);
    }
    @Test void replaceReleasesWholeReceiptsAndAppendsAuditAndCalculationAtomically() {
        confirmDefaults();var before=corrections.detail(owner,event);
        var p=corrections.preview(owner,event,preview(before,Action.REPLACE,List.of(second)));
        assertThat(p.changes()).singleElement().satisfies(c->{assertThat(c.receiptId()).isEqualTo(first);assertThat(c.afterStatus()).isEqualTo("PENDING");assertThat(c.afterReflected()).isTrue();});
        var saved=corrections.decide(owner,event,request(before,Action.REPLACE,List.of(second),UUID.randomUUID()));
        assertThat(saved.runId()).isNotNull();assertThat(state(first)).isEqualTo("PENDING");assertThat(state(second)).isEqualTo("RECEIVED");
        assertThat(count("receipt_correction_decisions")).isEqualTo(1);assertThat(count("engine_jobs")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt_allocation_history WHERE action='RELEASE'",Long.class)).isEqualTo(1);
        String snapshot=jdbc.queryForObject("SELECT selection_snapshot FROM calculation_runs WHERE id=?",String.class,saved.runId());
        var node=mapper.readTree(snapshot);if(node.isString()) node=mapper.readTree(node.asText());
        assertThat(node.path("request").path("plannedReceipts")).hasSize(1);
        assertThat(matching.report(original.getId()).matchedReceipts()).hasSize(2);
    }
    @Test void replaceCanExplicitlyAssignNewWholeReceiptAndNoMoreThanSelected() {
        long third=receipt(70);var d=corrections.detail(owner,event);
        corrections.decide(owner,event,request(d,Action.REPLACE,List.of(first,third),UUID.randomUUID()));
        assertThat(state(first)).isEqualTo("RECEIVED");assertThat(state(second)).isEqualTo("PENDING");assertThat(state(third)).isEqualTo("RECEIVED");
        assertThat(corrections.detail(owner,event).allocations()).extracting(Allocation::receiptId).containsExactly(first,third);
        assertThat(count("receipt_allocation_history")).isEqualTo(4);
    }
    @Test void emptyReplaceClearsAllAndSameSourceCannotAutomaticallyReallocate() {
        var d=corrections.detail(owner,event);
        corrections.decide(owner,event,request(d,Action.REPLACE,List.of(),UUID.randomUUID()));
        assertThat(state(first)).isEqualTo("PENDING");assertThat(state(second)).isEqualTo("PENDING");
        long newOrder=receipt(10);complete(upload(),200,true);
        assertThat(corrections.detail(owner,event).currentState()).isEqualTo("RESOLVED");
        assertThat(state(newOrder)).isEqualTo("PENDING");assertThat(count("receipt_matching_allocations")).isZero();
    }
    @Test void identicalSourceReuploadKeepsAcknowledgmentButRealChangeReopensEvenIfRestored() {
        corrections.decide(owner,event,request(corrections.detail(owner,event),Action.KEEP,List.of(),UUID.randomUUID()));
        complete(upload(),200,true);assertThat(corrections.detail(owner,event).currentState()).isEqualTo("RESOLVED");
        complete(upload(),160,true);assertThat(corrections.detail(owner,event).currentState()).isEqualTo("OPEN");
        complete(upload(),200,true);assertThat(corrections.detail(owner,event).currentState()).isEqualTo("OPEN");
        assertThat(count("receipt_matching_allocations")).isEqualTo(2);
        assertThat(corrections.detail(owner,event).lastDecision()).isNotNull();
    }
    @Test void keepUnderQuantityDoesNotHideShortageAndInvalidSourceCanOnlyKeepOrClear() {
        complete(upload(),120,true);var d=corrections.detail(owner,event);
        assertThat(d.warnings()).contains("INSUFFICIENT_QUANTITY");
        corrections.decide(owner,event,request(d,Action.KEEP,List.of(),UUID.randomUUID()));
        assertThat(corrections.detail(owner,event).warnings()).contains("INSUFFICIENT_QUANTITY");
        var invalid=upload();complete(invalid,200,false);d=corrections.detail(owner,event);
        assertThat(d.currentSource().valid()).isFalse();assertThat(d.capabilities().replace()).isFalse();assertThat(d.capabilities().clear()).isTrue();
        assertConflictOrInput(()->corrections.preview(owner,event,preview(corrections.detail(owner,event),Action.REPLACE,List.of(first))),"INVALID_INPUT");
        corrections.decide(owner,event,request(d,Action.REPLACE,List.of(),UUID.randomUUID()));
        assertThat(corrections.detail(owner,event).warnings()).contains("INVALID_OBSERVATION");
    }
    @Test void zeroSourceAndMissingProductDoNotAllowNewAllocationAndKeepEvidence() {
        complete(upload(),0,true);var zero=corrections.detail(owner,event);
        assertThat(zero.currentSource().present()).isTrue();assertThat(zero.capabilities().replace()).isFalse();
        var missing=upload();cleaning.complete(missing.getId(),missing.getJobId(),new CleaningResult(DAY,DAY,DAY,List.of(),List.of(),List.of(),List.of(),new CleaningResult.Validation(0,0,List.of()),null));
        var d=corrections.detail(owner,event);assertThat(d.currentSource().present()).isFalse();assertThat(d.warnings()).contains("SOURCE_MISSING");
        corrections.decide(owner,event,request(d,Action.KEEP,List.of(),UUID.randomUUID()));
        assertThat(corrections.detail(owner,event).warnings()).contains("SOURCE_MISSING","INVALID_OBSERVATION");
        assertThat(count("receipt_matching_allocations")).isEqualTo(2);
    }
    @Test void pendingAndFailedLatestRevisionBlockPreviewAndKeepCannotBypass() {
        var old=corrections.detail(owner,event);var pending=upload();
        var d=corrections.detail(owner,event);assertThat(d.sourceReady()).isFalse();
        assertConflictOrInput(()->corrections.preview(owner,event,preview(old,Action.KEEP,List.of())),"RECEIPT_CORRECTION_STALE");
        assertConflictOrInput(()->corrections.preview(owner,event,preview(corrections.detail(owner,event),Action.KEEP,List.of())),"RECEIPT_CORRECTION_UNAVAILABLE");
        cleaning.fail(pending.getId(),pending.getJobId(),UploadCleaningService.Failure.INVALID_SOURCE);
        assertThat(corrections.detail(owner,event).sourceReady()).isFalse();
        assertThat(count("receipt_correction_decisions")).isZero();
    }
    @Test void duplicateRequestReturnsOriginalResponseEvenAfterNewSourceAndDoesNotRecalculateTwice() {
        confirmDefaults();var d=corrections.detail(owner,event);var request=request(d,Action.REPLACE,List.of(),UUID.randomUUID());
        Saved saved=corrections.decide(owner,event,request);
        complete(upload(),80,true);
        assertThat(corrections.decide(owner,event,request)).isEqualTo(saved);
        assertThat(count("receipt_correction_decisions")).isEqualTo(1);assertThat(count("engine_jobs")).isEqualTo(1);
    }
    @Test void reusedKeyWithDifferentBodyConflictsWithoutWrites() {
        var d=corrections.detail(owner,event);UUID key=UUID.randomUUID();
        corrections.decide(owner,event,request(d,Action.KEEP,List.of(),key));
        assertConflictOrInput(()->corrections.decide(owner,event,request(d,Action.REPLACE,List.of(),key)),"RECEIPT_CORRECTION_KEY_CONFLICT");
        assertThat(count("receipt_correction_decisions")).isEqualTo(1);assertThat(count("receipt_matching_allocations")).isEqualTo(2);
    }
    @Test void expiredWriteAccessStillAllowsReplayOfAlreadyCommittedRequest() {
        var r=request(corrections.detail(owner,event),Action.KEEP,List.of(),UUID.randomUUID());
        var saved=corrections.decide(owner,event,r);
        doThrow(ProductException.forbidden()).when(billing).assertWriteAllowed(owner);
        assertThat(corrections.decide(owner,event,r)).isEqualTo(saved);
        verify(billing,times(1)).assertWriteAllowed(owner);
    }
    @Test void lateSupersededCleanCannotReopenOrRewriteManualDecision() {
        var late=upload();var newest=upload();complete(newest,210,true);
        corrections.decide(owner,event,request(corrections.detail(owner,event),Action.KEEP,List.of(),UUID.randomUUID()));
        long audit=count("receipt_allocation_history");String version=corrections.detail(owner,event).version();
        complete(late,80,true);
        assertThat(matching.report(late.getId()).receiptMatching().status()).isEqualTo("SUPERSEDED");
        assertThat(corrections.detail(owner,event).currentState()).isEqualTo("RESOLVED");
        assertThat(corrections.detail(owner,event).version()).isEqualTo(version);
        assertThat(count("receipt_allocation_history")).isEqualTo(audit);
    }
    @Test void newerDecisionAndChangedOrderInvalidatePreview() {
        long third=receipt(10);var old=corrections.detail(owner,event);
        orders.update(owner,third,new com.ipgo.server.dto.request.PlannedReceiptUpdateRequest(20L,null));
        assertConflictOrInput(()->corrections.preview(owner,event,preview(old,Action.REPLACE,List.of(third))),"RECEIPT_CORRECTION_STALE");
        var d=corrections.detail(owner,event);corrections.decide(owner,event,request(d,Action.KEEP,List.of(),UUID.randomUUID()));
        assertConflictOrInput(()->corrections.decide(owner,event,request(d,Action.KEEP,List.of(),UUID.randomUUID())),"RECEIPT_CORRECTION_STALE");
    }
    @Test void duplicateOverQuantityForeignAndSameDayReceiptsAreRejected() {
        long big=receipt(201);long sameDay=receipt(10);
        jdbc.update("UPDATE planned_receipts SET created_at=? WHERE id=?",OffsetDateTime.parse("2026-08-31T15:00:00Z"),sameDay);
        long other=UploadTestDatabase.newUser(jdbc,UUID.randomUUID()+"@example.test");
        jdbc.update("INSERT INTO products(user_id,sku_barcode,sku_name) VALUES (?,'other','기타')",other);
        long foreign=jdbc.queryForObject("SELECT id FROM products WHERE user_id=?",Long.class,other);
        jdbc.update("INSERT INTO warehouses(user_id,name) VALUES (?,'다른 창고')",other);
        long otherWarehouse=jdbc.queryForObject("SELECT id FROM warehouses WHERE user_id=?",Long.class,other);
        long foreignReceipt=receipts.saveAndFlush(new PlannedReceipt(foreign,otherWarehouse,1,DAY.plusDays(4))).getId();
        for(var ids:List.of(List.of(first,first),List.of(big),List.of(first,second,big),List.of(sameDay),List.of(foreignReceipt)))
            assertConflictOrInput(()->corrections.preview(owner,event,preview(corrections.detail(owner,event),Action.REPLACE,ids)),"INVALID_INPUT");
        assertConflictOrInput(()->corrections.preview(owner,event,preview(corrections.detail(owner,event),Action.KEEP,List.of(first))),"INVALID_INPUT");
        assertThat(count("receipt_correction_decisions")).isZero();
    }
    @Test void anotherEventsReceivedReceiptCannotBeMoved() {
        long r=receipt(30);
        jdbc.update("INSERT INTO receipt_matching_events(user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id) VALUES (?,?,?,?,30,TRUE,?)",owner,warehouse,product,DAY.plusDays(1),changed.getId());
        long otherEvent=jdbc.queryForObject("SELECT id FROM receipt_matching_events WHERE stock_date=?",Long.class,DAY.plusDays(1));
        jdbc.update("UPDATE planned_receipts SET status='RECEIVED',received_on=? WHERE id=?",DAY.plusDays(1),r);
        jdbc.update("INSERT INTO receipt_matching_allocations(receipt_id,event_id,warehouse_id,product_id,quantity,sku_name) VALUES (?,?,?,?,30,'상품A')",r,otherEvent,warehouse,product);
        assertConflictOrInput(()->corrections.preview(owner,event,preview(corrections.detail(owner,event),Action.REPLACE,List.of(r))),"INVALID_INPUT");
    }
    @Test void ownerAuthenticationInputAndContractValidation() throws Exception {
        long other=UploadTestDatabase.newUser(jdbc,UUID.randomUUID()+"@example.test");
        mvc.perform(get("/api/receipt-corrections/{id}",event)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/receipt-corrections/{id}",event).with(jwt().jwt(j->j.subject(""+other)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/receipt-corrections").with(jwt().jwt(j->j.subject(""+other)))).andExpect(jsonPath("items").isEmpty());
        mvc.perform(get("/api/receipt-corrections?size=51").with(jwt().jwt(j->j.subject(""+owner)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/receipt-corrections/{id}/decisions",event).with(jwt().jwt(j->j.subject(""+owner)))
                .contentType("application/json").content("{\"expectedVersion\":\"bad\",\"action\":\"KEEP\",\"receiptIds\":[],\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        var d=corrections.detail(owner,event);
        mvc.perform(post("/api/receipt-corrections/{id}/preview",event).with(jwt().jwt(j->j.subject(""+owner)))
                .contentType("application/json").content(mapper.writeValueAsString(preview(d,Action.REPLACE,List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("after").isEmpty()).andExpect(jsonPath("changes.length()").value(2));
        mvc.perform(post("/api/receipt-corrections/{id}/decisions",event).with(jwt().jwt(j->j.subject(""+owner)))
                .contentType("application/json").content(mapper.writeValueAsString(request(d,Action.KEEP,List.of(),UUID.randomUUID()))))
                .andExpect(status().isOk()).andExpect(jsonPath("currentState").value("RESOLVED"));
    }
    @Test void paginationDoesNotExposeOtherOwnersAndResolvedFilterIsCurrentNotOldU2() {
        jdbc.update("INSERT INTO receipt_matching_events(user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id,review_required) VALUES (?,?,?,?,1,TRUE,?,TRUE)",owner,warehouse,product,DAY.plusDays(1),changed.getId());
        var page=corrections.list(owner,State.OPEN,0,1);assertThat(page.items()).hasSize(1);assertThat(page.nextCursor()).isEqualTo(event);
        assertThat(corrections.list(owner,State.OPEN,page.nextCursor(),1).items()).hasSize(1);
        corrections.decide(owner,event,request(corrections.detail(owner,event),Action.KEEP,List.of(),UUID.randomUUID()));
        assertThat(corrections.list(owner,State.RESOLVED,0,20).items()).extracting(Detail::reviewId).containsExactly(event);
        assertThat(matching.report(changed.getId()).receiptMatching().issues()).extracting(i->i.reason()).contains("SOURCE_CHANGED");
    }
    @Test void billingDenialLeavesNoDecisionOrProjectionChange() {
        doThrow(ProductException.forbidden()).when(billing).assertWriteAllowed(owner);
        var d=corrections.detail(owner,event);
        assertConflictOrInput(()->corrections.decide(owner,event,request(d,Action.REPLACE,List.of(),UUID.randomUUID())),"FORBIDDEN");
        assertThat(count("receipt_correction_decisions")).isZero();assertThat(count("receipt_matching_allocations")).isEqualTo(2);
    }
    @Test void historyFailureRollsBackProjectionDecisionAndCalculationOutbox() {
        confirmDefaults();var d=corrections.detail(owner,event);long history=count("receipt_allocation_history");fault("audit",true);
        try { assertThatThrownBy(()->corrections.decide(owner,event,request(d,Action.REPLACE,List.of(),UUID.randomUUID()))).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { fault("audit",false); }
        assertThat(count("receipt_matching_allocations")).isEqualTo(2);assertThat(count("receipt_correction_decisions")).isZero();
        assertThat(count("receipt_allocation_history")).isEqualTo(history);assertThat(count("calculation_runs")).isZero();assertThat(count("engine_jobs")).isZero();
        assertThat(state(first)).isEqualTo("RECEIVED");assertThat(corrections.detail(owner,event).version()).isEqualTo(d.version());
    }
    @Test void outboxFailureCanRetrySameKeyAfterRollbackWithoutPartialAudit() {
        confirmDefaults();var d=corrections.detail(owner,event);var r=request(d,Action.REPLACE,List.of(),UUID.randomUUID());fault("outbox",true);
        try { assertThatThrownBy(()->corrections.decide(owner,event,r)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { fault("outbox",false); }
        assertThat(count("receipt_correction_decisions")).isZero();assertThat(count("calculation_runs")).isZero();
        assertThat(corrections.decide(owner,event,r).runId()).isNotNull();assertThat(count("engine_jobs")).isEqualTo(1);
    }
    @Test void concurrentSameKeyReturnsOneDecisionAndOneCalculation() throws Exception {
        confirmDefaults();var r=request(corrections.detail(owner,event),Action.REPLACE,List.of(),UUID.randomUUID());
        var results=race(()->corrections.decide(owner,event,r),()->corrections.decide(owner,event,r));
        assertThat(results.get(0)).isInstanceOf(Saved.class).isEqualTo(results.get(1));
        assertThat(count("receipt_correction_decisions")).isEqualTo(1);assertThat(count("engine_jobs")).isEqualTo(1);
    }
    @Test void concurrentDifferentDecisionsHaveOneWinnerWithoutDoubleRelease() throws Exception {
        var d=corrections.detail(owner,event);
        var results=race(()->corrections.decide(owner,event,request(d,Action.REPLACE,List.of(),UUID.randomUUID())),
                ()->corrections.decide(owner,event,request(d,Action.KEEP,List.of(),UUID.randomUUID())));
        assertThat(results.stream().filter(x->x instanceof Saved)).hasSize(1);assertThat(results.stream().filter(x->x instanceof ProductException)).hasSize(1);
        assertThat(count("receipt_correction_decisions")).isEqualTo(1);
    }
    @Test void concurrentCancellationAndDecisionHaveConsistentSingleWinner() throws Exception {
        long third=receipt(30);var d=corrections.detail(owner,event);
        var outcomes=race(()->corrections.decide(owner,event,request(d,Action.REPLACE,List.of(third),UUID.randomUUID())),()->{orders.cancel(owner,third);return "CANCELLED";});
        assertThat(outcomes.stream().filter(x->x instanceof ProductException)).hasSize(1);
        if(state(third).equals("CANCELLED")) { assertThat(count("receipt_correction_decisions")).isZero();assertThat(count("receipt_matching_allocations")).isEqualTo(2); }
        else { assertThat(state(third)).isEqualTo("RECEIVED");assertThat(corrections.detail(owner,event).allocations()).extracting(Allocation::receiptId).containsExactly(third); }
    }
    @Test void cleanAndDecisionSerializeOnWarehouseAndNewSourceInvalidatesOldPreview() throws Exception {
        var d=corrections.detail(owner,event);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->tx.execute(status->{jdbc.queryForObject("SELECT id FROM warehouses WHERE id=? FOR UPDATE",Long.class,warehouse);held.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); } catch(InterruptedException e){throw new RuntimeException(e);}
                complete(upload(),170,true);return true;}));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var reader=pool.submit(()->{try{return corrections.decide(owner,event,request(d,Action.REPLACE,List.of(),UUID.randomUUID()));}catch(ProductException e){return e;}});
            release.countDown();assertThat(writer.get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(reader.get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(ProductException.class,e->assertThat(e.code()).isEqualTo("RECEIPT_CORRECTION_STALE"));
        } finally { release.countDown(); }
        assertThat(count("receipt_correction_decisions")).isZero();assertThat(corrections.detail(owner,event).currentSource().quantity()).isEqualTo(170);
    }
    @Test void postgresServerCannotRewriteAuditOrCrossScope() {
        Assumptions.assumeTrue(isPostgres());
        var saved=corrections.decide(owner,event,request(corrections.detail(owner,event),Action.KEEP,List.of(),UUID.randomUUID()));
        assertThatThrownBy(()->jdbc.update("UPDATE receipt_correction_decisions SET reason='changed'")).rootCause()
                .isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(()->jdbc.update("DELETE FROM receipt_allocation_history")).rootCause()
                .isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
        jdbc.update("INSERT INTO warehouses(user_id,name) VALUES (?,'범위 검사')",owner);
        long otherWarehouse=jdbc.queryForObject("SELECT MAX(id) FROM warehouses WHERE user_id=?",Long.class,owner);
        assertThatThrownBy(()->jdbc.update("""
                INSERT INTO receipt_correction_decisions(id,event_id,user_id,warehouse_id,product_id,source_upload_file_id,
                    source_fingerprint,action,reason,idempotency_key,request_hash,previous_version,snapshot,response)
                SELECT ?,event_id,user_id,?,product_id,source_upload_file_id,source_fingerprint,action,'scope test',?,
                    request_hash,previous_version,snapshot,response FROM receipt_correction_decisions WHERE id=?
                """,UUID.randomUUID().toString(),otherWarehouse,UUID.randomUUID().toString(),saved.decisionId())).rootCause()
                .isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23503"));
        jdbc.update("INSERT INTO receipt_matching_events(user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id) VALUES (?,?,?,?,100,TRUE,?)",
                owner,warehouse,product,DAY.plusDays(1),changed.getId());
        long otherEvent=jdbc.queryForObject("SELECT id FROM receipt_matching_events WHERE warehouse_id=? AND stock_date=?",Long.class,warehouse,DAY.plusDays(1));
        assertThatThrownBy(()->jdbc.update("""
                INSERT INTO receipt_allocation_history(event_id,receipt_id,warehouse_id,product_id,quantity,sku_name,received_on,action,decision_id)
                VALUES (?,?,?,?,100,'상품A',?,'ASSIGN',?)
                """,otherEvent,first,warehouse,product,DAY.plusDays(1),saved.decisionId())).rootCause()
                .isInstanceOfSatisfying(java.sql.SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23503"));
        assertThat(count("receipt_correction_decisions")).isEqualTo(1);
    }

    private void fault(String target,boolean enabled) {
        if(isPostgres()) { jdbc.queryForObject("SELECT ipgo_test_correction_fault(?,?)",Object.class,target,enabled);return; }
        String table=target.equals("audit")?"receipt_allocation_history":"engine_jobs";
        String condition=target.equals("audit")?"action<>'RELEASE'":"job_type<>'CALCULATE'";
        jdbc.execute("ALTER TABLE "+table+(enabled?" ADD CONSTRAINT correction_test_fault CHECK ("+condition+")":" DROP CONSTRAINT correction_test_fault"));
    }
    private boolean isPostgres() {
        try(var c=jdbc.getDataSource().getConnection()){return c.getMetaData().getDatabaseProductName().equals("PostgreSQL");}
        catch(java.sql.SQLException e){throw new IllegalStateException(e);}
    }
    private List<Object> race(Callable<?> a,Callable<?> b) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(a,b).stream().map(task->pool.submit(()->{start.await();try{return task.call();}catch(ProductException e){return e;}})).toList();
            start.countDown();var values=new ArrayList<Object>();for(var f:futures)values.add(f.get(20,TimeUnit.SECONDS));return values;
        }
    }
    private void assertConflictOrInput(Runnable r,String code) { assertThatThrownBy(r::run).isInstanceOfSatisfying(ProductException.class,e->assertThat(e.code()).isEqualTo(code)); }
    private ReceiptCorrectionRequest.Preview preview(Detail d,Action action,List<Long> ids) {return new ReceiptCorrectionRequest.Preview(d.version(),action,ids);}
    private ReceiptCorrectionRequest.Decide request(Detail d,Action action,List<Long> ids,UUID key) {return new ReceiptCorrectionRequest.Decide(d.version(),action,ids,"수불 정정 확인",key);}
    private void confirmDefaults(){jdbc.update("UPDATE warehouses SET default_lead_time_days=3,default_review_cycle_days=7,default_order_unit_qty=1 WHERE id=?",warehouse);}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private String state(long id){return receipts.findById(id).orElseThrow().getStatus();}
    private long receipt(long quantity){var r=receipts.saveAndFlush(new PlannedReceipt(product,warehouse,quantity,DAY.plusDays(10)));jdbc.update("UPDATE planned_receipts SET created_at=? WHERE id=?",OffsetDateTime.parse("2026-08-30T00:00:00Z"),r.getId());return r.getId();}
    private UploadFile upload(){return uploads.saveAndFlush(new UploadFile(warehouse,"2026-09.xlsx",UUID.randomUUID().toString(),"a".repeat(64),uploads.lastRevision(warehouse,DAY)+1,new UploadPeriod(YearMonth.from(DAY))));}
    private void complete(UploadFile u,long quantity,boolean valid) {
        var validation=valid?new CleaningResult.Validation(1,1,List.of()):new CleaningResult.Validation(1,0,List.of(new CleaningResult.ExcludedSku("A","상품A",DAY,"검산 제외")));
        cleaning.complete(u.getId(),u.getJobId(),new CleaningResult(DAY,DAY,DAY,
                List.of(new CleaningResult.CleanProduct("A","상품A",100L,true)),List.of(new CleaningResult.Unit("LOT1","A",DAY)),
                List.of(new CleaningResult.UnitDay(u.getId(),"LOT1",DAY,DAY,0L,quantity,0L,0L,quantity,"test",1)),
                List.of(new CleaningResult.ProductDay(u.getId(),"A",DAY,0L,quantity,0L,0L,quantity,false,null)),validation,null));
    }
}
