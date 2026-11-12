package com.ipgo.server.service;

import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.repository.WarehouseRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** #197. 정제 완료/명시적 AUTO 선택 트랜잭션에서만 호출한다. */
@Service
public class ProductActivityService {
    private final UploadFileRepository uploads;
    private final WarehouseRepository warehouses;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ProductActivityService(UploadFileRepository uploads, WarehouseRepository warehouses,
            NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.uploads = uploads;
        this.warehouses = warehouses;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluate(long userId, long warehouseId, List<Product> products) {
        var warehouse = warehouses.findByIdForUpdate(warehouseId).orElseThrow(ProductException::notFound);
        if (!warehouse.getUserId().equals(userId)) throw ProductException.forbidden();
        var latest = uploads.findLatestPerPeriod(warehouseId);
        var newest = latest.stream().max(Comparator.comparing(UploadFile::getPeriodStart));
        if (newest.isEmpty() || !"DONE".equals(newest.get().getStatus())) return;
        var sources = latest.stream().filter(u -> "DONE".equals(u.getStatus())).toList();
        LocalDate end = sources.stream().map(UploadFile::getPeriodEnd).max(LocalDate::compareTo).orElseThrow();
        if (end.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) return;
        LocalDate start = end.minusDays(27);
        var params = new MapSqlParameterSource("sources", sources.stream().map(UploadFile::getId).toList())
                .addValue("user", userId).addValue("start", start).addValue("end", end);
        var covered = new HashSet<>(jdbc.query("""
                SELECT DISTINCT d.stock_date FROM product_daily_stocks d
                JOIN products p ON p.id = d.product_id
                WHERE p.user_id = :user AND d.upload_file_id IN (:sources)
                  AND d.stock_date BETWEEN :start AND :end
                """, params, (rs, row) -> rs.getDate(1).toLocalDate()));
        boolean complete = start.datesUntil(end.plusDays(1)).allMatch(covered::contains);
        Map<Long, Presence> presence = new HashMap<>();
        jdbc.query("""
                SELECT d.product_id, MIN(d.stock_date) AS first_seen,
                    MAX(CASE WHEN d.stock_date BETWEEN :start AND :end THEN 1 ELSE 0 END) AS recent
                FROM product_daily_stocks d JOIN products p ON p.id = d.product_id
                WHERE p.user_id = :user AND d.upload_file_id IN (:sources)
                GROUP BY d.product_id
                """, params, rs -> {
                    presence.put(rs.getLong("product_id"),
                            new Presence(rs.getDate("first_seen").toLocalDate(), rs.getInt("recent") == 1));
                });
        for (Product product : products) {
            if (!product.getUserId().equals(userId)) throw ProductException.forbidden();
            if (!"AUTO".equals(product.getActivationMode())) continue;
            var found = presence.get(product.getId());
            if (found == null) continue;
            if (found.recent()) product.applyAutomaticActivity(true);
            else if (complete && found.firstSeen().isBefore(start)) product.applyAutomaticActivity(false);
        }
    }

    private record Presence(LocalDate firstSeen, boolean recent) { }
}
