package com.ipgo.server.service;

import com.ipgo.server.config.DemandShiftProperties;
import com.ipgo.server.dto.response.DemandShift;
import com.ipgo.server.entity.CalculationRun;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

// #193 판매 급변. 계산 결과(run)마다 그 계산에 쓴 수불부(run_source_files)의 하루 출고로 판단한다.
// 예: 기준일 11/10, 최근 7일(11/04~11/10) 하루 평균 21개, 그 전 28일(10/07~11/03) 평균 2.6개 → 8.1배 · 18.4개 늘어 UP
// - UP: 최근 평균 ≥ 이전 평균 × upRatio, 그리고 최근 − 이전 ≥ upMinIncrease(저판매 상품의 0→2개 같은 작은 변화는 뺀다)
// - DOWN: 직전 계산에서 UP이던 상품이 이번에는 UP이 아니고, 권장량이 직전의 downRatio 배 이하로 줄었을 때
// - 평균은 기록이 있는 날로 나눈다. 이전 구간 기록이 recentDays일보다 적으면(새 상품 등) 판단하지 않는다.
@Service
@EnableConfigurationProperties(DemandShiftProperties.class)
public class DemandShiftService {
    private final NamedParameterJdbcTemplate jdbc;
    private final DemandShiftProperties rule;

    public DemandShiftService(NamedParameterJdbcTemplate jdbc, DemandShiftProperties rule) {
        this.jdbc = jdbc;
        this.rule = rule;
    }

    // 상품 ID → 판매 급변. 기준에 맞는 상품만 들어 있다.
    public Map<Long, DemandShift> forRun(CalculationRun run) {
        Map<Long, Window> now = windows(run.getId(), run.getAsOfDate());
        Map<Long, DemandShift> shifts = new HashMap<>();
        now.forEach((product, window) -> {
            if (up(window)) shifts.put(product, shift(window, DemandShift.UP, null));
        });
        Long previous = previousRun(run);
        if (previous == null) return shifts;
        LocalDate previousAsOf = jdbc.queryForObject("SELECT as_of_date FROM calculation_runs WHERE id = :run",
                new MapSqlParameterSource("run", previous), LocalDate.class);
        Map<Long, Long> previousQty = recommended(previous), currentQty = recommended(run.getId());
        windows(previous, previousAsOf).forEach((product, window) -> {
            // 이번 계산의 기록이 부족해 판단하지 않은 상품은 "급증이 끝났다"고 볼 수 없으니 DOWN도 아니다.
            Window current = now.get(product);
            if (!up(window) || current == null || shifts.containsKey(product)) return;
            Long before = previousQty.get(product), after = currentQty.get(product);
            if (before == null || before <= 0 || after == null) return;
            if (BigDecimal.valueOf(after).compareTo(BigDecimal.valueOf(before).multiply(rule.downRatio())) > 0) return;
            shifts.put(product, shift(current, DemandShift.DOWN, before));
        });
        return shifts;
    }

    private LocalDate sinceOf(LocalDate asOf) {
        return asOf.minusDays(rule.recentDays() - 1L);
    }

    private Map<Long, Window> windows(long runId, LocalDate asOf) {
        LocalDate since = sinceOf(asOf);
        var params = new MapSqlParameterSource("run", runId).addValue("since", since).addValue("asOf", asOf)
                .addValue("baselineStart", since.minusDays(rule.baselineDays()));
        Map<Long, Window> windows = new HashMap<>();
        jdbc.query("""
                SELECT d.product_id,
                    SUM(CASE WHEN d.stock_date >= :since THEN d.outbound_qty ELSE 0 END) AS recent_qty,
                    SUM(CASE WHEN d.stock_date >= :since THEN 1 ELSE 0 END) AS recent_days,
                    SUM(CASE WHEN d.stock_date < :since THEN d.outbound_qty ELSE 0 END) AS baseline_qty,
                    SUM(CASE WHEN d.stock_date < :since THEN 1 ELSE 0 END) AS baseline_days
                FROM product_daily_stocks d
                WHERE d.upload_file_id IN (SELECT r.upload_file_id FROM run_source_files r WHERE r.run_id = :run)
                  AND d.stock_date BETWEEN :baselineStart AND :asOf
                GROUP BY d.product_id
                """, params, rs -> {
            int recentDays = rs.getInt("recent_days"), baselineDays = rs.getInt("baseline_days");
            if (recentDays == 0 || baselineDays < rule.recentDays()) return;
            windows.put(rs.getLong("product_id"), new Window(average(rs.getBigDecimal("recent_qty"), recentDays),
                    average(rs.getBigDecimal("baseline_qty"), baselineDays), since));
        });
        return windows;
    }

    private Map<Long, Long> recommended(long runId) {
        Map<Long, Long> qty = new HashMap<>();
        jdbc.query("SELECT product_id, recommended_qty FROM calculation_results WHERE run_id = :run",
                new MapSqlParameterSource("run", runId), rs -> {
                    long value = rs.getLong("recommended_qty");
                    qty.put(rs.getLong("product_id"), rs.wasNull() ? null : value);
                });
        return qty;
    }

    // 같은 창고에서 이 계산 바로 전에 성공한 계산
    private Long previousRun(CalculationRun run) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM calculation_runs WHERE warehouse_id = :warehouse AND status = 'DONE' AND id < :run
                ORDER BY id DESC FETCH FIRST 1 ROWS ONLY
                """, new MapSqlParameterSource("warehouse", run.getWarehouseId()).addValue("run", run.getId()), Long.class);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static BigDecimal average(BigDecimal total, int days) {
        return (total == null ? BigDecimal.ZERO : total).divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
    }

    private record Window(BigDecimal recent, BigDecimal baseline, LocalDate since) { }

    private boolean up(Window window) {
        return window.recent().compareTo(window.baseline().multiply(rule.upRatio())) >= 0
                && window.recent().subtract(window.baseline()).compareTo(rule.upMinIncrease()) >= 0;
    }

    private DemandShift shift(Window window, String direction, Long previousRecommendedQty) {
        BigDecimal ratio = window.baseline().signum() == 0 ? null
                : window.recent().divide(window.baseline(), 1, RoundingMode.HALF_UP);
        return new DemandShift(direction, oneDecimal(window.recent()), oneDecimal(window.baseline()), ratio,
                window.since(), previousRecommendedQty);
    }

    private static BigDecimal oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP);
    }
}
