package com.ipgo.server.service;

import com.ipgo.server.exception.ProductException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Explicit calls only. Five immutable rows are the lifetime budget; no reset, queue or provider retry. */
@Service
public class InsightBatch implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InsightBatch.class);
    private static final String IDS = InsightBatchManifest.TARGETS.stream().map(t -> Long.toString(t.resultId()))
            .collect(java.util.stream.Collectors.joining(",","(",")"));
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final StoredInsightFacts facts;
    private final InsightGeneration generation;
    private final ProductDetailService details;
    private final TransactionTemplate claim;
    private final boolean enabled;
    private final Duration timeout;
    private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("insight-batch-recovery").factory());

    public InsightBatch(JdbcTemplate jdbc, JsonMapper mapper, StoredInsightFacts facts, InsightGeneration generation,
            ProductDetailService details, TransactionTemplate tx,
            @Value("${ipgo.insight.batch-enabled:false}") boolean enabled,
            @Value("${ipgo.insight.enabled:false}") boolean automatic,
            @Value("${ipgo.insight.worker-enabled:true}") boolean worker,
            @Value("${ipgo.insight.single-result-enabled:false}") boolean single,
            @Value("${ipgo.insight.timeout:15s}") Duration timeout) {
        if (enabled && (automatic || worker || single))
            throw new IllegalArgumentException("Batch requires automatic insight, worker and single-result mode disabled");
        if (InsightBatchManifest.TARGETS.size() != InsightBatchManifest.MAX_CALLS
                || InsightBatchManifest.TARGETS.stream().map(InsightBatchManifest.Target::resultId).distinct().count() != InsightBatchManifest.MAX_CALLS
                || InsightBatchManifest.TARGETS.stream().map(InsightBatchManifest.Target::productId).distinct().count() != InsightBatchManifest.MAX_CALLS
                || InsightBatchManifest.TARGETS.stream().anyMatch(t -> t.resultId() == 811))
            throw new IllegalArgumentException("Invalid fixed batch manifest");
        this.jdbc=jdbc; this.mapper=mapper; this.facts=facts; this.generation=generation;
        this.details=details; this.enabled=enabled; this.timeout=timeout;
        claim=new TransactionTemplate(tx.getTransactionManager());
        claim.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Item(long productId, long resultId, String state) { }
    public record Preview(String batchId, boolean enabled, int maximumCalls, int reservedAttempts,
                          int remainingAttempts, String gate, List<Item> targets) { }

    /** Read-only even when disabled: no reservations, fallback recovery or provider calls. */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Preview preview(long owner, String batchId) {
        authorizeScope(owner,batchId);
        var rows=InsightBatchManifest.TARGETS.stream().map(t -> {
            String state;
            try { checkReady(owner,t); state="READY"; }
            catch(ProductException e) { state=e.code(); }
            return new Item(t.productId(),t.resultId(),state);
        }).toList();
        int reserved=reserved();
        String gate=gate();
        return new Preview(batchId,enabled,InsightBatchManifest.MAX_CALLS,reserved,
                gate.equals("OPEN") ? Math.min(InsightBatchManifest.MAX_CALLS-reserved,(int)rows.stream().filter(t -> t.state().equals("READY")).count()) : 0,gate,rows);
    }

    public void generate(long owner, String batchId, long resultId) {
        authorizeScope(owner,batchId);
        if(!enabled) throw ProductException.insightBatchDisabled();
        var target=InsightBatchManifest.TARGETS.stream().filter(t -> t.resultId()==resultId).findFirst()
                .orElseThrow(ProductException::forbidden);
        claim.executeWithoutResult(tx -> {
            // One shared lock order serializes claims across threads/replicas. Commit before HTTP.
            var locked=jdbc.queryForList("SELECT result_id FROM ai_explanations WHERE result_id IN " + IDS + " ORDER BY result_id FOR UPDATE",Long.class);
            if(locked.size()!=InsightBatchManifest.MAX_CALLS) throw ProductException.insightInputUnavailable();
            checkReady(owner,target);
            if(reserved()>=InsightBatchManifest.MAX_CALLS) throw ProductException.insightSpent();
            if(!gate().equals("OPEN")) throw ProductException.insightBatchStopped();
            var p=facts.restore(resultId,owner);
            jdbc.update("""
                    UPDATE ai_explanations SET status='PROCESSING',reason_code=NULL,validation_passed=FALSE,
                      request_payload=CAST(? AS JSONB),output_path=?,created_at=CURRENT_TIMESTAMP
                    WHERE result_id=?
                    """,mapper.writeValueAsString(Map.of("input",p.input(),"values",p.values(),"fallback",p.fallback())),
                    InsightBatchManifest.marker(resultId),resultId);
        });
        generation.processClaimed(resultId); // Exactly once after durable reservation. Never resume this HTTP on recovery.
    }

    private void authorizeScope(long owner,String batchId) {
        if(owner!=InsightBatchManifest.OWNER || !InsightBatchManifest.ID.equals(batchId)) throw ProductException.forbidden();
    }

    private void checkReady(long owner,InsightBatchManifest.Target target) {
        var owners=jdbc.query("""
                SELECT p.user_id,p.id,w.user_id FROM calculation_results r JOIN products p ON p.id=r.product_id
                JOIN calculation_runs cr ON cr.id=r.run_id JOIN warehouses w ON w.id=cr.warehouse_id WHERE r.id=?
                """,(rs,n) -> new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)},target.resultId());
        if(owners.isEmpty()) throw ProductException.insightInputUnavailable();
        var o=owners.getFirst();
        if(o[0]!=owner || o[2]!=owner || o[1]!=target.productId()) throw ProductException.forbidden();
        int ready=jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_explanations WHERE result_id=? AND mode='NONE' AND status='DISABLED'
                AND reason_code='LLM_DISABLED' AND output_path IS NULL AND raw_text IS NULL AND rendered_text IS NULL
                AND (cost_usd IS NULL OR cost_usd=0) AND input_tokens IS NULL AND output_tokens IS NULL
                """,Integer.class,target.resultId());
        if(ready!=1) throw ProductException.insightSpent();
        var current=details.detail(owner,target.productId(),null,null).result();
        if(current==null || current.resultId()!=target.resultId()) throw ProductException.insightResultStale();
        facts.restore(target.resultId(),owner);
    }

    private int reserved() {
        return (int)InsightBatchManifest.TARGETS.stream().filter(this::reserved).count();
    }
    private boolean reserved(InsightBatchManifest.Target t) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_explanations WHERE result_id=? AND output_path=?",Integer.class,
                t.resultId(),InsightBatchManifest.marker(t.resultId()))==1;
    }
    private String gate() {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_explanations WHERE result_id IN " + IDS,Integer.class)
                !=InsightBatchManifest.MAX_CALLS) return "INPUT_UNAVAILABLE";
        for(var t:InsightBatchManifest.TARGETS) {
            var states=jdbc.query("SELECT status,mode,validation_passed FROM ai_explanations WHERE result_id=? AND output_path=?",
                    (rs,n) -> rs.getString(1).equals("PROCESSING") ? "IN_PROGRESS" :
                            rs.getString(1).equals("DONE") && rs.getString(2).equals("LLM") && rs.getBoolean(3) ? "OPEN" : "STOPPED_AFTER_FAILURE",
                    t.resultId(),InsightBatchManifest.marker(t.resultId()));
            if(!states.isEmpty() && !states.getFirst().equals("OPEN")) return states.getFirst();
        }
        return "OPEN";
    }

    @jakarta.annotation.PostConstruct
    public void start() { if(enabled) recovery.scheduleWithFixedDelay(this::recoverSafely,1,1,TimeUnit.SECONDS); }
    private void recoverSafely() {
        try { recover(); } catch(RuntimeException e) { log.warn("Batch insight recovery deferred; error_type={}",e.getClass().getSimpleName()); }
    }
    public void recover() {
        if(!enabled) return;
        for(var t:InsightBatchManifest.TARGETS) {
            int expired=jdbc.queryForObject("SELECT COUNT(*) FROM ai_explanations WHERE result_id=? AND output_path=? AND status='PROCESSING' AND created_at < ?",
                    Integer.class,t.resultId(),InsightBatchManifest.marker(t.resultId()),Timestamp.from(Instant.now().minus(timeout).minusSeconds(30)));
            if(expired==1) generation.expireClaimed(t.resultId());
        }
    }
    @jakarta.annotation.PreDestroy public void close() { recovery.shutdownNow(); }
}
