package com.ipgo.server.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable monthly admission shared by every caller; failed/unknown requests never refund a slot. */
@Component
public class MonthlyInsightBudget {
    static final BigDecimal RESERVATION_USD = new BigDecimal("0.01");
    static final ZoneId MONTH_ZONE = ZoneId.of("Asia/Seoul");
    enum Decision { GRANTED, EXHAUSTED, ALREADY_DISPATCHED }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final long callLimit;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public MonthlyInsightBudget(JdbcTemplate jdbc, TransactionTemplate tx,
            @Value("${ipgo.insight.monthly-budget-usd:10}") BigDecimal budget) {
        this(jdbc, tx, budget, Clock.systemUTC());
    }

    MonthlyInsightBudget(JdbcTemplate jdbc, TransactionTemplate tx, BigDecimal budget, Clock clock) {
        if (budget == null || budget.signum() < 0 || budget.compareTo(BigDecimal.TEN) > 0)
            throw new IllegalArgumentException("Monthly insight budget must be between USD 0 and 10");
        this.jdbc = jdbc; this.tx = tx; this.clock = clock;
        this.callLimit = budget.divide(RESERVATION_USD, 0, RoundingMode.DOWN).longValueExact();
    }

    synchronized Decision tryDispatch(String attempt) {
        return tx.execute(status -> {
            boolean postgres = Boolean.TRUE.equals(jdbc.execute(
                    (org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                            "PostgreSQL".equals(c.getMetaData().getDatabaseProductName())));
            // Serializes all processes before counting/reserving; no provider HTTP occurs under this lock.
            if (postgres) jdbc.execute("SELECT pg_advisory_xact_lock(74893201513)");
            var rows = jdbc.queryForList("SELECT status,dispatched_at FROM ai_explanation_calls WHERE attempt_id=? FOR UPDATE", attempt);
            if (rows.isEmpty()) return Decision.ALREADY_DISPATCHED;
            Map<String,Object> row = rows.getFirst();
            if (!"PROCESSING".equals(row.get("status")) || row.get("dispatched_at") != null)
                return Decision.ALREADY_DISPATCHED;
            Instant now = postgres ? jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant() : clock.instant();
            var month = now.atZone(MONTH_ZONE).withDayOfMonth(1).toLocalDate().atStartOfDay(MONTH_ZONE);
            var key = month.toLocalDate();
            jdbc.update("INSERT INTO insight_monthly_usage(month_key,reserved_calls) SELECT ?,0 WHERE NOT EXISTS (SELECT 1 FROM insight_monthly_usage WHERE month_key=?)",key,key);
            Integer count = jdbc.queryForObject("SELECT reserved_calls FROM insight_monthly_usage WHERE month_key=? FOR UPDATE",Integer.class,key);
            if (count == null || count >= callLimit) return Decision.EXHAUSTED;
            if (jdbc.update("UPDATE ai_explanation_calls SET dispatched_at=? WHERE attempt_id=? AND dispatched_at IS NULL AND status='PROCESSING'",
                    Timestamp.from(now), attempt) != 1) return Decision.ALREADY_DISPATCHED;
            if (jdbc.update("UPDATE insight_monthly_usage SET reserved_calls=reserved_calls+1,updated_at=? WHERE month_key=? AND reserved_calls=?",
                    Timestamp.from(now),key,count) != 1) throw new IllegalStateException("Monthly reservation lost");
            return Decision.GRANTED;
        });
    }
}
