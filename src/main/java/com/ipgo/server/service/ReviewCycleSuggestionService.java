package com.ipgo.server.service;

import com.ipgo.server.dto.response.ProductSettings;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.repository.UploadFileRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

// 조회 전용 제안. 설정 저장/계산 시작은 사용자의 S2 확인 뒤에만 수행한다.
// 입고 주기는 최근 12개월의 날짜별 입고 기록으로 추정한다(#166). 마지막 달 하나만 보면 한 달에 1~2번 들어오는 셀러는 늘 비었다.
// 규칙은 엔진 estimate_review_cycle_days와 같다: 상품마다 입고일(입고 수량 > 0, 같은 날은 하루) 3개 이상이면
// 간격 중앙값, 그 값들의 중앙값을 반올림. 파일에서 검산 제외된 상품은 그 파일 기록을 쓰지 않는다.
@Service
public class ReviewCycleSuggestionService {
    static final int WINDOW_MONTHS = 12;
    static final int MIN_INBOUND_DAYS = 3;

    public record Estimate(Integer reviewCycleDays, LocalDate from, LocalDate to, int productCount) {
        static final Estimate NONE = new Estimate(null, null, null, 0);
    }

    private final UploadFileRepository uploads;
    private final NamedParameterJdbcTemplate jdbc;

    public ReviewCycleSuggestionService(UploadFileRepository uploads, NamedParameterJdbcTemplate jdbc) {
        this.uploads = uploads;
        this.jdbc = jdbc;
    }

    public ProductSettings defaults(Optional<Warehouse> warehouse) {
        ProductSettings stored = ProductSettings.defaults(warehouse);
        if (warehouse.isEmpty() || warehouse.get().hasConfirmedDefaults()) return stored;
        return new ProductSettings(stored.leadTimeDays(), estimate(warehouse.get()).reviewCycleDays(), stored.orderUnitQty());
    }

    // 셀러가 기본값을 확인하기 전에만 의미가 있다. 확인 뒤에는 저장한 값을 쓴다.
    public Estimate estimate(Warehouse warehouse) {
        // revision 선택을 DONE 필터보다 먼저 수행한다. 실패/처리 중 정정의 옛 값을 되살리지 않는다.
        List<UploadFile> sources = uploads.findLatestPerPeriod(warehouse.getId()).stream()
                .filter(u -> "DONE".equals(u.getStatus())).toList();
        if (sources.isEmpty()) return Estimate.NONE;
        Map<Long, Set<Long>> excludedByUpload = new HashMap<>();
        for (UploadFile source : sources) {
            var report = source.getValidationReport();
            Set<Long> excluded = new HashSet<>();
            if (report != null && report.validation() != null && report.validation().excludedSkus() != null) {
                report.validation().excludedSkus().forEach(sku -> excluded.add(sku.productId()));
            }
            excludedByUpload.put(source.getId(), excluded);
        }
        var params = new MapSqlParameterSource("uploads", excludedByUpload.keySet());
        LocalDate to = jdbc.queryForObject(
                "SELECT MAX(stock_date) FROM product_daily_stocks WHERE upload_file_id IN (:uploads)", params, LocalDate.class);
        if (to == null) return Estimate.NONE;
        LocalDate from = to.minusMonths(WINDOW_MONTHS).plusDays(1);
        Map<Long, TreeSet<LocalDate>> inboundDays = new HashMap<>();
        jdbc.query("""
                SELECT product_id, upload_file_id, stock_date FROM product_daily_stocks
                WHERE upload_file_id IN (:uploads) AND inbound_qty > 0 AND stock_date BETWEEN :from AND :to
                """, params.addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)), rs -> {
            long product = rs.getLong(1);
            if (excludedByUpload.get(rs.getLong(2)).contains(product)) return;
            inboundDays.computeIfAbsent(product, p -> new TreeSet<>()).add(rs.getObject(3, LocalDate.class));
        });
        List<Double> cycles = new ArrayList<>();
        for (TreeSet<LocalDate> days : inboundDays.values()) {
            if (days.size() < MIN_INBOUND_DAYS) continue;
            List<Double> gaps = new ArrayList<>();
            LocalDate previous = null;
            for (LocalDate day : days) {
                if (previous != null) gaps.add((double) ChronoUnit.DAYS.between(previous, day));
                previous = day;
            }
            cycles.add(median(gaps));
        }
        if (cycles.isEmpty()) return new Estimate(null, from, to, 0);
        return new Estimate((int) Math.floor(median(cycles) + 0.5), from, to, cycles.size());
    }

    // Python statistics.median과 같다: 개수가 짝수면 가운데 두 값의 평균.
    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
