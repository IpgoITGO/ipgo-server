package com.ipgo.server.service;

import com.ipgo.server.exception.ProductException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

/** Fixed one-result, lifetime one-attempt exception. Never enables or drains the global queue. */
@Service
public class InsightRegeneration implements AutoCloseable {
    public static final long RESULT_ID = 811;
    static final long BUDGET_ANCHOR_ID = 698;
    static final String BUDGET_MARKER = "single-budget/698-to-811/attempt-1";
    static final String ATTEMPT = "single-result/811/attempt-1";
    private static final Logger log = LoggerFactory.getLogger(InsightRegeneration.class);
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final StoredInsightFacts facts;
    private final InsightGeneration generation;
    private final ProductDetailService details;
    private final TransactionTemplate claim;
    private final boolean enabled;
    private final Duration timeout;
    private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("insight-single-recovery").factory());

    public InsightRegeneration(JdbcTemplate jdbc, JsonMapper mapper, StoredInsightFacts facts, InsightGeneration generation, ProductDetailService details,
            TransactionTemplate tx, @Value("${ipgo.insight.single-result-enabled:false}") boolean enabled,
            @Value("${ipgo.insight.enabled:false}") boolean automatic,
            @Value("${ipgo.insight.worker-enabled:true}") boolean worker,
            @Value("${ipgo.insight.timeout:15s}") Duration timeout) {
        if (enabled && (automatic || worker)) throw new IllegalArgumentException("Single-result mode requires automatic insight and worker disabled");
        this.jdbc=jdbc; this.mapper=mapper; this.facts=facts; this.generation=generation; this.details=details; this.enabled=enabled; this.timeout=timeout;
        claim = new TransactionTemplate(tx.getTransactionManager());
        claim.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void regenerate(long owner, long id) {
        if (!enabled) throw ProductException.insightDisabled();
        if (id != RESULT_ID) throw ProductException.forbidden();
        claim.executeWithoutResult(tx -> {
            long product=authorize(owner,id,false);
            authorize(owner,BUDGET_ANCHOR_ID,true);
            // Same order as old 698 claims. Both grants are committed atomically before HTTP.
            var locked=jdbc.queryForList("SELECT result_id FROM ai_explanations WHERE result_id IN (?,?) ORDER BY result_id FOR UPDATE",
                    Long.class,BUDGET_ANCHOR_ID,RESULT_ID);
            if(locked.size()!=2) throw ProductException.insightInputUnavailable();
            requireUnspent(BUDGET_ANCHOR_ID); requireUnspent(id);
            requireCurrent(owner,product,id);
            var p=facts.restore(id,owner);
            String payload=mapper.writeValueAsString(Map.of("input",p.input(),"values",p.values(),"fallback",p.fallback()));
            int budget=jdbc.update("""
                    UPDATE ai_explanations SET output_path=? WHERE result_id=? AND output_path IS NULL
                    AND mode='NONE' AND status='DISABLED' AND reason_code='LLM_DISABLED'
                    """,BUDGET_MARKER,BUDGET_ANCHOR_ID);
            if(budget!=1) throw ProductException.insightSpent();
            int updated=jdbc.update("""
                    UPDATE ai_explanations SET output_path=?,status='PROCESSING',request_payload=CAST(? AS JSONB),
                    validation_passed=FALSE,reason_code=NULL,created_at=CURRENT_TIMESTAMP
                    WHERE result_id=? AND output_path IS NULL AND status='DISABLED' AND reason_code='LLM_DISABLED'
                    """,ATTEMPT,payload,id);
            if(updated!=1) throw ProductException.insightSpent();
            generation.recordClaim(id);
        });
        // Old 698 code also rejects the consumed anchor after rollback. Never call both targets.
        generation.processClaimed(id);
    }

    public record Preview(long resultId, long productId, boolean enabled, int remainingAttempts) { }

    /** Read-only preflight; the POST repeats all checks under the two row locks. */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Preview preview(long owner,long id) {
        if(id!=RESULT_ID) throw ProductException.forbidden();
        long product=authorize(owner,id,false);
        authorize(owner,BUDGET_ANCHOR_ID,true);
        int rows=jdbc.queryForObject("SELECT COUNT(*) FROM ai_explanations WHERE result_id IN (?,?)",Integer.class,BUDGET_ANCHOR_ID,id);
        if(rows!=2) throw ProductException.insightInputUnavailable();
        requireUnspent(BUDGET_ANCHOR_ID); requireUnspent(id);
        requireCurrent(owner,product,id);
        facts.restore(id,owner);
        return new Preview(id,product,enabled,1);
    }

    private long authorize(long owner,long id,boolean anchor) {
        var rows=jdbc.query("""
                SELECT p.user_id,p.id FROM calculation_results r JOIN products p ON p.id=r.product_id
                JOIN calculation_runs cr ON cr.id=r.run_id JOIN warehouses w ON w.id=cr.warehouse_id
                WHERE r.id=? AND w.user_id=p.user_id
                """,(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},id);
        if(rows.isEmpty()) {
            if(anchor) throw ProductException.insightInputUnavailable();
            throw ProductException.notFound();
        }
        if(rows.getFirst()[0]!=owner) throw ProductException.forbidden();
        return rows.getFirst()[1];
    }

    private void requireUnspent(long id) {
        int ready=jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_explanations WHERE result_id=? AND mode='NONE' AND status='DISABLED'
                AND reason_code='LLM_DISABLED' AND output_path IS NULL AND raw_text IS NULL AND rendered_text IS NULL
                AND (cost_usd IS NULL OR cost_usd=0) AND input_tokens IS NULL AND output_tokens IS NULL
                """,Integer.class,id);
        if(ready!=1) throw ProductException.insightSpent();
    }

    private void requireCurrent(long owner,long product,long id) {
        var current=details.detail(owner,product,null,null).result();
        if(current==null||current.resultId()!=id) throw ProductException.insightResultStale();
    }

    @jakarta.annotation.PostConstruct
    public void start() { if(enabled) recovery.scheduleWithFixedDelay(this::recoverSafely,1,1,TimeUnit.SECONDS); }
    private void recoverSafely() {
        try { recover(); } catch(RuntimeException e) { log.warn("Single insight recovery deferred; error_type={}",e.getClass().getSimpleName()); }
    }
    public void recover() {
        if(!enabled) return;
        var expired=jdbc.queryForList("""
                SELECT result_id FROM ai_explanations WHERE
                ((result_id=? AND output_path=?) OR (result_id=? AND output_path=?))
                AND status='PROCESSING' AND created_at < ?
                """,Long.class,RESULT_ID,ATTEMPT,BUDGET_ANCHOR_ID,"single-result/698/attempt-1",Timestamp.from(Instant.now().minus(timeout).minusSeconds(30)));
        for(long id:expired) generation.expireClaimed(id); // Recovery saves fallback only; never calls the provider.
    }
    @jakarta.annotation.PreDestroy public void close() { recovery.shutdownNow(); }
}
