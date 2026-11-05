package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.service.EngineJobs;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

// EngineJobs의 시간 SQL을 실제 PostgreSQL에서도 확인한다(IPGO_TEST_DB_URL). H2만으로는 매개변수 타입 문제가 안 보인다.
class EngineJobsDatabaseTests extends DatabaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired EngineJobs jobs;

    @BeforeEach void table() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS engine_jobs (job_id VARCHAR(100) PRIMARY KEY, job_type VARCHAR(20) NOT NULL,
              target_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, payload JSONB NOT NULL, status VARCHAR(20) NOT NULL,
              attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
              created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at TIMESTAMP WITH TIME ZONE)
            """);
        jdbc.update("DELETE FROM engine_jobs");
    }
    @AfterEach void clean() { jdbc.update("DELETE FROM engine_jobs"); }

    private String job(String type, long ageSeconds) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO engine_jobs(job_id,job_type,target_id,warehouse_id,payload,status,created_at) VALUES (?,?,1,1,CAST('{}' AS JSONB),'PENDING',?)",
                id, type, Timestamp.from(Instant.now().minusSeconds(ageSeconds)));
        return id;
    }

    @Test void expiryAndResendWindowsWorkOnTheRealDatabase() {
        String oldCalc = job("CALCULATE", 16 * 60), newCalc = job("CALCULATE", 60), oldClean = job("CLEAN", 16 * 60);
        assertThat(jobs.expired()).contains(oldCalc).doesNotContain(newCalc, oldClean);
        jobs.sent(newCalc); jobs.sent(oldClean);
        Instant now = Instant.now();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM engine_jobs WHERE job_id=?", Timestamp.class, newCalc).toInstant())
                .isBetween(now.plusSeconds(240), now.plusSeconds(360));
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM engine_jobs WHERE job_id=?", Timestamp.class, oldClean).toInstant())
                .isBetween(now.plusSeconds(1140), now.plusSeconds(1260));
        assertThat(jdbc.queryForObject("SELECT status FROM engine_jobs WHERE job_id=?", String.class, newCalc)).isEqualTo("SENT");
    }
}
