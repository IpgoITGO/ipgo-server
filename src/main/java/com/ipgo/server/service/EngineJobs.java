package com.ipgo.server.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Durable outbox. Call enqueue inside the same transaction as the domain change. */
@Service
public class EngineJobs {
    public record Job(String id, String type, long targetId, long warehouseId, JsonNode payload, String status) { }
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final boolean enabled;

    public EngineJobs(JdbcTemplate jdbc, JsonMapper mapper, @Value("${ipgo.engine.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String id, String type, long targetId, long warehouseId, Object payload) {
        if (!enabled) return;
        String json = mapper.writeValueAsString(payload);
        // Bound both the DB snapshot and SQS envelope. No truncation of business input.
        if (json.getBytes(StandardCharsets.UTF_8).length > 850_000) {
            throw new IllegalArgumentException("Engine input is too large");
        }
        jdbc.update("""
                INSERT INTO engine_jobs(job_id, job_type, target_id, warehouse_id, payload, status)
                VALUES (?, ?, ?, ?, CAST(? AS JSONB), 'PENDING')
                """, id, type, targetId, warehouseId, json);
    }

    public List<Job> due() {
        return jdbc.query("""
                SELECT job_id, job_type, target_id, warehouse_id, payload, status FROM engine_jobs
                WHERE status IN ('PENDING', 'SENT') AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY created_at FETCH FIRST 5 ROWS ONLY
                """, (rs, n) -> new Job(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        readJson(rs.getString(5)), rs.getString(6)));
    }

    public String envelope(Job job) {
        return mapper.writeValueAsString(Map.of("contractVersion", "1", "jobId", job.id(),
                "jobType", job.type(), "payload", job.payload()));
    }

    // A short lease prevents multiple dispatchers sending at once. A crash expires and retries the same jobId.
    public boolean claim(String id) {
        return jdbc.update("""
                UPDATE engine_jobs SET next_attempt_at = ?, attempts = attempts + 1
                WHERE job_id = ? AND status IN ('PENDING', 'SENT') AND next_attempt_at <= CURRENT_TIMESTAMP
                """, Timestamp.from(Instant.now().plusSeconds(60)), id) == 1;
    }

    public void sent(String id) {
        jdbc.update("UPDATE engine_jobs SET status = 'SENT', next_attempt_at = ? WHERE job_id = ? AND status IN ('PENDING','SENT')",
                Timestamp.from(Instant.now().plusSeconds(1200)), id);
    }

    public List<String> expired() {
        return jdbc.queryForList("""
                SELECT job_id FROM engine_jobs WHERE status IN ('PENDING','SENT') AND created_at < ?
                ORDER BY created_at FETCH FIRST 5 ROWS ONLY
                """, String.class, Timestamp.from(Instant.now().minusSeconds(7200)));
    }

    public Job find(String id) {
        List<Job> jobs = jdbc.query("SELECT job_id, job_type, target_id, warehouse_id, payload, status FROM engine_jobs WHERE job_id = ?",
                (rs, n) -> new Job(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        readJson(rs.getString(5)), rs.getString(6)), id);
        if (jobs.size() != 1) throw new IllegalArgumentException("Unknown engine job");
        return jobs.getFirst();
    }

    static JsonNode readDatabaseJson(JsonMapper mapper, String text) {
        JsonNode node = mapper.readTree(text);
        // H2's PostgreSQL compatibility stores CAST(varchar AS JSONB) as a JSON string.
        return node.isString() ? mapper.readTree(node.asText()) : node;
    }
    private JsonNode readJson(String text) { return readDatabaseJson(mapper, text); }

    public void finished(String id, boolean success) {
        jdbc.update("UPDATE engine_jobs SET status = ?, completed_at = CURRENT_TIMESTAMP WHERE job_id = ?",
                success ? "DONE" : "FAILED", id);
    }
}
