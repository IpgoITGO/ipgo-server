package com.ipgo.server.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Called under the latest explanation row lock in the caller's transaction. */
final class InsightCallHistory {
    private final JdbcTemplate jdbc;
    InsightCallHistory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    String begin(long id, String source) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Insight history requires a transaction");
        String attempt = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO ai_explanation_calls(attempt_id,result_id,source,started_at,status,
                    request_payload,output_path,mode,validation_passed,versions)
                SELECT ?,result_id,?,created_at,'PROCESSING',request_payload,output_path,mode,FALSE,versions
                FROM ai_explanations WHERE result_id=? AND status='PROCESSING'
                """, attempt, source, id);
        if (inserted != 1) throw new IllegalStateException("Missing insight claim");
        jdbc.update("UPDATE ai_explanations SET active_attempt_id=? WHERE result_id=?", attempt, id);
        return attempt;
    }

    void finish(long id, String attempt, Integer roundTrip) {
        int updated = jdbc.update("""
                UPDATE ai_explanation_calls SET status='DONE',finished_at=CURRENT_TIMESTAMP,round_trip_ms=?,
                    (mode,raw_text,validation_passed,inserted_values,rendered_text,reason_code,
                     model_name,versions,input_tokens,output_tokens,latency_ms,cost_usd) =
                    (SELECT mode,raw_text,validation_passed,inserted_values,rendered_text,reason_code,
                     model_name,versions,input_tokens,output_tokens,latency_ms,cost_usd
                     FROM ai_explanations WHERE result_id=?)
                WHERE attempt_id=? AND result_id=? AND status='PROCESSING'
                """, roundTrip, id, attempt, id);
        if (updated != 1) throw new IllegalStateException("Missing open insight call");
    }
}
