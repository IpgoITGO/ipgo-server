package com.ipgo.server.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class EngineTimingTest {
    private static final String ID = "6e0ec7a8-80d4-4e26-a1a3-0037b91d5832";
    private final Logger logger = (Logger) LoggerFactory.getLogger(EngineTiming.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EngineJobs.Job job = new EngineJobs.Job(ID, "CALCULATE", 328, 999, null, "SENT");
    private TransactionTemplate transaction;

    @BeforeEach void setup() {
        appender.start(); logger.addAppender(appender);
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:timing;DB_CLOSE_DELAY=-1", "sa", "");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @AfterEach void cleanup() { logger.detachAppender(appender); appender.stop(); }
    private tools.jackson.databind.JsonNode singleEvent() {
        assertThat(appender.list).hasSize(1);
        return mapper.readTree(appender.list.getFirst().getFormattedMessage());
    }

    @Test void transactionEventIsEmittedOnlyAfterCommitAndCarriesSafeCorrelation() {
        transaction.executeWithoutResult(status -> {
            var observation = EngineTiming.observeResult(true, job, System.nanoTime());
            observation.processing(EngineTiming.Processing.SUCCEEDED);
            assertThat(appender.list).isEmpty();
        });
        var event = singleEvent();
        assertThat(event.path("jobId").asText()).isEqualTo(ID);
        assertThat(event.path("runId").asLong()).isEqualTo(328);
        assertThat(event.path("processing").asText()).isEqualTo("succeeded");
        assertThat(event.path("transaction").asText()).isEqualTo("committed");
        assertThat(event.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(event.has("warehouseId")).isFalse();
    }
    @Test void outerRollbackCannotBeReportedAsCommit() {
        transaction.executeWithoutResult(status -> {
            transaction.executeWithoutResult(inner -> {
                var observation = EngineTiming.observeResult(true, job, System.nanoTime());
                observation.processing(EngineTiming.Processing.SUCCEEDED);
            });
            assertThat(appender.list).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(singleEvent().path("transaction").asText()).isEqualTo("rolled_back");
    }
    @Test void duplicateAndFailedOutcomesRemainDistinct() {
        transaction.executeWithoutResult(status -> EngineTiming.observeResult(true, job, System.nanoTime())
                .processing(EngineTiming.Processing.DUPLICATE));
        assertThat(singleEvent().path("processing").asText()).isEqualTo("duplicate");
        appender.list.clear();
        transaction.executeWithoutResult(status -> EngineTiming.observeResult(true, job, System.nanoTime())
                .processing(EngineTiming.Processing.FAILED));
        var event = singleEvent();
        assertThat(event.path("processing").asText()).isEqualTo("failed");
        assertThat(event.path("transaction").asText()).isEqualTo("committed");
    }
    @Test void optInAndInvalidUntrustedJobIdPreventLogging() {
        EngineTiming.dispatch(false, job, System.nanoTime(), true);
        var unsafe = new EngineJobs.Job("sensitive\nvalue", "CALCULATE", 328, 999, null, "SENT");
        EngineTiming.dispatch(true, unsafe, System.nanoTime(), false);
        transaction.executeWithoutResult(status -> EngineTiming.observeResult(true, unsafe, System.nanoTime()));
        assertThat(appender.list).isEmpty();
        EngineTiming.dispatch(true, job, System.nanoTime(), false);
        assertThat(singleEvent().path("outcome").asText()).isEqualTo("send_failed");
    }
}
