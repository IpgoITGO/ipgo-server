package com.ipgo.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Executes the production SQL with real JDBC binding; the temporary table is connection-local. */
class EngineJobsJdbcTests {
    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;
    EngineJobs jobs;

    @BeforeEach void connect() throws Exception {
        boolean postgres = "true".equals(System.getenv("IPGO_ENGINE_JDBC_POSTGRES"));
        String url = postgres
                ? "jdbc:postgresql://ipgo-engine-jdbc-102-db:5432/engine_test"
                : "jdbc:h2:mem:engine-jobs-jdbc;MODE=PostgreSQL";
        dataSource = new SingleConnectionDataSource(url, postgres ? "engine_test" : "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        if (postgres) jdbc.execute("SET TIME ZONE 'Asia/Seoul'");
        var metadata = dataSource.getConnection().getMetaData();
        System.out.println("EngineJobs JDBC: " + metadata.getDatabaseProductName() + " "
                + metadata.getDatabaseProductVersion() + "; driver=" + metadata.getDriverVersion());
        jdbc.execute("""
                CREATE TEMPORARY TABLE engine_jobs (
                    job_id VARCHAR(100) PRIMARY KEY, job_type VARCHAR(20) NOT NULL,
                    target_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, payload JSONB NOT NULL,
                    status VARCHAR(20) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMP WITH TIME ZONE)
                """);
        jobs = new EngineJobs(jdbc, JsonMapper.builder().build(), true);
    }

    @AfterEach void close() { if (dataSource != null) dataSource.destroy(); }

    void insert(String id, String type, String status, Instant created) {
        jdbc.update("""
                INSERT INTO engine_jobs(job_id,job_type,target_id,warehouse_id,payload,status,created_at)
                VALUES (?,?,1,1,CAST('{}' AS JSONB),?,?)
                """, id, type, status, Timestamp.from(created));
    }

    @ParameterizedTest
    @CsvSource({
            "CLEAN,PENDING,7190,false", "CLEAN,PENDING,7210,true",
            "CLEAN,SENT,7190,false", "CLEAN,SENT,7210,true",
            "CALCULATE,PENDING,890,false", "CALCULATE,PENDING,910,true",
            "CALCULATE,SENT,890,false", "CALCULATE,SENT,910,true"
    })
    void expirationKeepsEachJobTypeDeadline(String type, String status, long age, boolean expired) {
        insert("job", type, status, Instant.now().minusSeconds(age));
        assertThat(jobs.expired()).isEqualTo(expired ? java.util.List.of("job") : java.util.List.of());
        assertThat(jdbc.queryForObject("SELECT status FROM engine_jobs", String.class)).isEqualTo(status);
    }

    @ParameterizedTest
    @CsvSource({"CLEAN,PENDING,1200", "CLEAN,SENT,1200", "CALCULATE,PENDING,300", "CALCULATE,SENT,300"})
    void sentPersistsTypedTimestampAndPreservesRetryPolicy(String type, String status, long delay) {
        insert("job", type, status, Instant.now());
        Instant before = Instant.now();
        jobs.sent("job");
        Instant after = Instant.now();
        Instant next = jdbc.queryForObject("SELECT next_attempt_at FROM engine_jobs", Timestamp.class).toInstant();
        assertThat(next).isBetween(before.plusSeconds(delay), after.plusSeconds(delay));
        assertThat(jdbc.queryForObject("SELECT status FROM engine_jobs", String.class)).isEqualTo("SENT");
        assertThat(jobs.due()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "FAILED"})
    void terminalJobsAreNeitherExpiredNorRescheduled(String status) {
        insert("job", "CALCULATE", status, Instant.now().minusSeconds(9000));
        Instant next = jdbc.queryForObject("SELECT next_attempt_at FROM engine_jobs", Timestamp.class).toInstant();
        assertThat(jobs.expired()).isEmpty();
        jobs.sent("job");
        assertThat(jdbc.queryForObject("SELECT status FROM engine_jobs", String.class)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM engine_jobs", Timestamp.class).toInstant()).isEqualTo(next);
    }

    @Test void expiredBatchIsOldestFirstAndBoundedToFive() {
        Instant now = Instant.now();
        for (int i = 0; i < 7; i++) insert("job-" + i, "CLEAN", "PENDING", now.minusSeconds(7300 + i));
        assertThat(jobs.expired()).containsExactly("job-6", "job-5", "job-4", "job-3", "job-2");
    }

    @Test void emptyExpirationQueryAllowsPendingCleanToBeDispatched() {
        insert("job", "CLEAN", "PENDING", Instant.now());
        assertThat(jobs.expired()).isEmpty();
        assertThat(jobs.due()).extracting(EngineJobs.Job::id).containsExactly("job");
        assertThat(jobs.claim("job")).isTrue();
        assertThat(jobs.claim("job")).isFalse();
        jobs.sent("job");
        assertThat(jdbc.queryForObject("SELECT attempts FROM engine_jobs", Integer.class)).isEqualTo(1);
    }
}
