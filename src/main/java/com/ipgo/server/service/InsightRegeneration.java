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
    public static final long RESULT_ID = 698;
    static final String ATTEMPT = "single-result/698/attempt-1";
    private static final Logger log = LoggerFactory.getLogger(InsightRegeneration.class);
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final StoredInsightFacts facts;
    private final InsightGeneration generation;
    private final TransactionTemplate claim;
    private final boolean enabled;
    private final Duration timeout;
    private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("insight-single-recovery").factory());

    public InsightRegeneration(JdbcTemplate jdbc, JsonMapper mapper, StoredInsightFacts facts, InsightGeneration generation,
            TransactionTemplate tx, @Value("${ipgo.insight.single-result-enabled:false}") boolean enabled,
            @Value("${ipgo.insight.enabled:false}") boolean automatic,
            @Value("${ipgo.insight.worker-enabled:true}") boolean worker,
            @Value("${ipgo.insight.timeout:15s}") Duration timeout) {
        if (enabled && (automatic || worker)) throw new IllegalArgumentException("Single-result mode requires automatic insight and worker disabled");
        this.jdbc=jdbc; this.mapper=mapper; this.facts=facts; this.generation=generation; this.enabled=enabled; this.timeout=timeout;
        claim = new TransactionTemplate(tx.getTransactionManager());
        claim.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void regenerate(long owner, long id) {
        if (!enabled) throw ProductException.insightDisabled();
        if (id != RESULT_ID) throw ProductException.forbidden();
        claim.executeWithoutResult(tx -> {
            var owners = jdbc.queryForList("""
                    SELECT p.user_id FROM calculation_results r JOIN products p ON p.id=r.product_id
                    JOIN calculation_runs cr ON cr.id=r.run_id JOIN warehouses w ON w.id=cr.warehouse_id
                    WHERE r.id=? AND w.user_id=p.user_id
                    """, Long.class, id);
            if (owners.isEmpty()) throw ProductException.notFound();
            if (owners.getFirst() != owner) throw ProductException.forbidden();
            var rows=jdbc.queryForList("SELECT result_id FROM ai_explanations WHERE result_id=? FOR UPDATE", Long.class, id);
            if (rows.size()!=1) throw ProductException.insightInputUnavailable();
            int eligible=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ai_explanations WHERE result_id=? AND mode='NONE' AND status='DISABLED'
                    AND reason_code='LLM_DISABLED' AND output_path IS NULL AND raw_text IS NULL AND rendered_text IS NULL
                    AND (cost_usd IS NULL OR cost_usd=0) AND input_tokens IS NULL AND output_tokens IS NULL
                    """, Integer.class, id);
            if (eligible!=1) throw ProductException.insightSpent();
            var p=facts.restore(id, owner);
            String payload=mapper.writeValueAsString(Map.of("input",p.input(),"values",p.values(),"fallback",p.fallback()));
            int updated=jdbc.update("""
                    UPDATE ai_explanations SET output_path=?,status='PROCESSING',request_payload=CAST(? AS JSONB),
                    validation_passed=FALSE,reason_code=NULL,created_at=CURRENT_TIMESTAMP
                    WHERE result_id=? AND output_path IS NULL AND status='DISABLED' AND reason_code='LLM_DISABLED'
                    """, ATTEMPT,payload,id);
            if(updated!=1) throw ProductException.insightSpent();
        });
        // The budget marker has committed before HTTP, even if this process dies or saving the response fails.
        generation.processClaimed(id);
    }

    public record Preview(long resultId, boolean enabled, int remainingAttempts) { }

    /** Read-only preflight is available while execution is disabled; POST always rechecks under lock. */
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Preview preview(long owner, long id) {
        if (id != RESULT_ID) throw ProductException.forbidden();
        var owners=jdbc.queryForList("""
                SELECT p.user_id FROM calculation_results r JOIN products p ON p.id=r.product_id
                JOIN calculation_runs cr ON cr.id=r.run_id JOIN warehouses w ON w.id=cr.warehouse_id
                WHERE r.id=? AND w.user_id=p.user_id
                """,Long.class,id);
        if(owners.isEmpty()) throw ProductException.notFound();
        if(owners.getFirst()!=owner) throw ProductException.forbidden();
        int ready=jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_explanations WHERE result_id=? AND mode='NONE' AND status='DISABLED'
                AND reason_code='LLM_DISABLED' AND output_path IS NULL AND raw_text IS NULL AND rendered_text IS NULL
                AND (cost_usd IS NULL OR cost_usd=0) AND input_tokens IS NULL AND output_tokens IS NULL
                """,Integer.class,id);
        if(ready!=1) throw ProductException.insightSpent();
        facts.restore(id,owner);
        return new Preview(id,enabled,1);
    }

    @jakarta.annotation.PostConstruct
    public void start() { if(enabled) recovery.scheduleWithFixedDelay(this::recoverSafely,1,1,TimeUnit.SECONDS); }
    private void recoverSafely() {
        try { recover(); } catch(RuntimeException e) { log.warn("Single insight recovery deferred; error_type={}",e.getClass().getSimpleName()); }
    }
    public void recover() {
        if(!enabled) return;
        var expired=jdbc.queryForList("""
                SELECT result_id FROM ai_explanations WHERE result_id=? AND output_path=? AND status='PROCESSING'
                AND created_at < ?
                """,Long.class,RESULT_ID,ATTEMPT,Timestamp.from(Instant.now().minus(timeout).minusSeconds(30)));
        for(long id:expired) generation.expireClaimed(id); // Recovery saves fallback only; never calls the provider.
    }
    @jakarta.annotation.PreDestroy public void close() { recovery.shutdownNow(); }
}
