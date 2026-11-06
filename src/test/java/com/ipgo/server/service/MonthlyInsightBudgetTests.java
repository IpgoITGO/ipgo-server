package com.ipgo.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class MonthlyInsightBudgetTests {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    AtomicReference<Instant> at;
    Clock clock;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:budget-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE ai_explanation_calls(attempt_id VARCHAR(50) PRIMARY KEY,status VARCHAR(20),dispatched_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE insight_monthly_usage(month_key DATE PRIMARY KEY,reserved_calls INTEGER,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        at = new AtomicReference<>(Instant.parse("2026-11-06T00:00:00Z"));
        clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return at.get(); }
        };
    }
    MonthlyInsightBudget budget(String usd) { return new MonthlyInsightBudget(jdbc,tx,new BigDecimal(usd),clock); }
    void attempt(String id) { jdbc.update("INSERT INTO ai_explanation_calls(attempt_id,status) VALUES (?,'PROCESSING')",id); }

    @Test void durableLimitIncludesFailedCallsAndSurvivesRestart() {
        attempt("a");attempt("b");attempt("c");
        assertThat(budget("0.02").tryDispatch("a")).isEqualTo(MonthlyInsightBudget.Decision.GRANTED);
        jdbc.update("UPDATE ai_explanation_calls SET status='DONE' WHERE attempt_id='a'");
        assertThat(budget("0.02").tryDispatch("b")).isEqualTo(MonthlyInsightBudget.Decision.GRANTED);
        assertThat(budget("0.02").tryDispatch("c")).isEqualTo(MonthlyInsightBudget.Decision.EXHAUSTED);
        assertThat(jdbc.queryForObject("SELECT dispatched_at IS NULL FROM ai_explanation_calls WHERE attempt_id='c'",Boolean.class)).isTrue();
    }
    @Test void duplicateAttemptNeverDispatchesTwice() {
        attempt("a");var b=budget("10");
        assertThat(b.tryDispatch("a")).isEqualTo(MonthlyInsightBudget.Decision.GRANTED);
        assertThat(b.tryDispatch("a")).isEqualTo(MonthlyInsightBudget.Decision.ALREADY_DISPATCHED);
    }
    @Test void deletingOldCallOrResultCannotRefundBudget() {
        attempt("a");attempt("b");var b=budget("0.01");
        assertThat(b.tryDispatch("a")).isEqualTo(MonthlyInsightBudget.Decision.GRANTED);
        jdbc.update("DELETE FROM ai_explanation_calls WHERE attempt_id='a'");
        assertThat(budget("0.01").tryDispatch("b")).isEqualTo(MonthlyInsightBudget.Decision.EXHAUSTED);
    }
    @Test void seoulMonthBoundaryRenewsAllowanceWithoutRefundingHistory() {
        at.set(Instant.parse("2026-10-31T14:59:59Z"));attempt("a");attempt("b");
        var b=budget("0.01");assertThat(b.tryDispatch("a")).isEqualTo(MonthlyInsightBudget.Decision.GRANTED);
        assertThat(b.tryDispatch("b")).isEqualTo(MonthlyInsightBudget.Decision.EXHAUSTED);
        at.set(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(b.tryDispatch("b")).isEqualTo(MonthlyInsightBudget.Decision.GRANTED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_explanation_calls WHERE dispatched_at IS NOT NULL",Long.class)).isEqualTo(2);
    }
    @Test void concurrentAdmissionCannotExceedAllowance() throws Exception {
        var b=budget("0.03");for(int n=0;n<10;n++)attempt("a"+n);
        try(var pool=Executors.newFixedThreadPool(5)) {
            var jobs=new ArrayList<java.util.concurrent.Callable<MonthlyInsightBudget.Decision>>();
            for(int n=0;n<10;n++){int id=n;jobs.add(()->b.tryDispatch("a"+id));}
            long granted=0;for(var result:pool.invokeAll(jobs))if(result.get()==MonthlyInsightBudget.Decision.GRANTED)granted++;
            assertThat(granted).isEqualTo(3);
        }
    }
    @Test void budgetIsFailClosedAndCannotExceedApprovedMaximum() {
        attempt("a");assertThat(budget("0").tryDispatch("a")).isEqualTo(MonthlyInsightBudget.Decision.EXHAUSTED);
        assertThatThrownBy(()->budget("10.01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->budget("-1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitlyUnlimitedModeIgnoresExhaustedQuotaButDoesNotRefundIt() {
        jdbc.update("INSERT INTO insight_monthly_usage(month_key,reserved_calls) VALUES (DATE '2026-11-01',1000)");
        attempt("unlimited");
        var b = new MonthlyInsightBudget(jdbc,tx,BigDecimal.TEN,clock,false);
        assertThat(b.tryDispatch("unlimited")).isEqualTo(MonthlyInsightBudget.Decision.GRANTED);
        assertThat(b.tryDispatch("unlimited")).isEqualTo(MonthlyInsightBudget.Decision.ALREADY_DISPATCHED);
        assertThat(jdbc.queryForObject("SELECT reserved_calls FROM insight_monthly_usage",Integer.class)).isEqualTo(1000);
    }
    @Test void unlimitedDispatchRequiresNoQuotaTableAndKeepsDuplicateSuppression() throws Exception {
        jdbc.execute("DROP TABLE insight_monthly_usage");attempt("unlimited");
        var b = new MonthlyInsightBudget(jdbc,tx,BigDecimal.TEN,clock,false);
        try(var pool = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<java.util.concurrent.Callable<MonthlyInsightBudget.Decision>>();
            for(int n=0;n<8;n++)jobs.add(()->b.tryDispatch("unlimited"));
            long granted=0;for(var r:pool.invokeAll(jobs))if(r.get()==MonthlyInsightBudget.Decision.GRANTED)granted++;
            assertThat(granted).isEqualTo(1);
        }
    }
}
