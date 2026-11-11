package com.ipgo.server.service;

import com.ipgo.server.dto.request.ExpirySettingsRequest;
import com.ipgo.server.dto.response.ExpiryList;
import com.ipgo.server.dto.response.ExpiryList.Status;
import com.ipgo.server.dto.response.ExpirySettings;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.UploadFileRepository;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// #168 유통기한 관리. 기준일(마지막 수불 날짜)에 남은 재고가 있는 LOT만 보여 준다.
// 업로드 선택은 다른 화면과 같다: 기간별 최신 revision을 고른 뒤 DONE만 쓴다.
@Service
public class ExpiryService {
    static final int DEFAULT_IMMINENT_DAYS = 30;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NamedParameterJdbcTemplate jdbc;
    private final WarehouseService warehouses;
    private final UploadFileRepository uploads;
    private final ProductRepository products;
    private final Clock clock;

    public ExpiryService(NamedParameterJdbcTemplate jdbc, WarehouseService warehouses, UploadFileRepository uploads,
            ProductRepository products, Clock clock) {
        this.jdbc = jdbc;
        this.warehouses = warehouses;
        this.uploads = uploads;
        this.products = products;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ExpirySettings settings(long userId) {
        return new ExpirySettings(warehouses.findDefault(userId).map(this::imminentDays).orElse(DEFAULT_IMMINENT_DAYS));
    }

    @Transactional
    public ExpirySettings saveSettings(long userId, ExpirySettingsRequest request) {
        Warehouse warehouse = warehouses.lockDefault(userId);
        jdbc.update("UPDATE warehouses SET expiry_imminent_days = :days WHERE id = :id",
                new MapSqlParameterSource("days", request.imminentDays()).addValue("id", warehouse.getId()));
        return new ExpirySettings(request.imminentDays());
    }

    @Transactional(readOnly = true)
    public ExpiryList list(long userId, Status status, Long productId) {
        if (productId != null) owned(userId, productId);
        var warehouse = warehouses.findDefault(userId);
        int imminentDays = warehouse.map(this::imminentDays).orElse(DEFAULT_IMMINENT_DAYS);
        List<Long> sources = warehouse.map(w -> uploads.findLatestPerPeriod(w.getId()).stream()
                .filter(u -> "DONE".equals(u.getStatus())).map(UploadFile::getId).toList()).orElse(List.of());
        if (sources.isEmpty()) return new ExpiryList(null, imminentDays, new ExpiryList.Summary(0, 0, 0, 0), List.of());

        var params = new MapSqlParameterSource("uploads", sources).addValue("userId", userId);
        LocalDate asOf = jdbc.queryForObject(
                "SELECT MAX(stock_date) FROM daily_stock_records WHERE upload_file_id IN (:uploads)", params, LocalDate.class);
        if (asOf == null) return new ExpiryList(null, imminentDays, new ExpiryList.Summary(0, 0, 0, 0), List.of());

        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        params.addValue("asOf", Date.valueOf(asOf));
        String productFilter = "";
        if (productId != null) {
            params.addValue("productId", productId);
            productFilter = " AND u.product_id = :productId";
        }
        List<ExpiryList.Item> lots = jdbc.query("""
                SELECT u.product_id, p.sku_barcode, p.sku_name, u.received_on, u.expiry_date, d.closing_qty
                FROM daily_stock_records d
                JOIN inventory_units u ON u.id = d.inventory_unit_id
                JOIN products p ON p.id = u.product_id
                WHERE d.upload_file_id IN (:uploads) AND d.stock_date = :asOf AND d.closing_qty > 0
                  AND p.user_id = :userId
                """ + productFilter, params, (rs, n) -> {
            LocalDate expiry = rs.getObject(5, LocalDate.class);
            Long daysLeft = expiry == null ? null : ChronoUnit.DAYS.between(today, expiry);
            return new ExpiryList.Item(rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getObject(4, LocalDate.class), expiry, daysLeft, rs.getLong(6), status(daysLeft, imminentDays));
        });
        var summary = new ExpiryList.Summary(count(lots, Status.EXPIRED), count(lots, Status.IMMINENT),
                count(lots, Status.OK), count(lots, Status.UNKNOWN));
        // 유통기한이 빠른 순, 모르는 LOT는 맨 뒤. 같으면 상품·입고일 순으로 고정한다.
        List<ExpiryList.Item> items = lots.stream()
                .filter(i -> status == null || i.status() == status)
                .sorted(Comparator.comparing(ExpiryList.Item::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ExpiryList.Item::productId)
                        .thenComparing(ExpiryList.Item::receivedOn))
                .toList();
        return new ExpiryList(asOf, imminentDays, summary, items);
    }

    // 지남: 유통기한이 오늘보다 이전. 임박: 오늘 ~ 오늘+기준일. 그 뒤는 괜찮음.
    static Status status(Long daysLeft, int imminentDays) {
        if (daysLeft == null) return Status.UNKNOWN;
        if (daysLeft < 0) return Status.EXPIRED;
        return daysLeft <= imminentDays ? Status.IMMINENT : Status.OK;
    }

    private int imminentDays(Warehouse warehouse) {
        Integer days = jdbc.queryForObject("SELECT expiry_imminent_days FROM warehouses WHERE id = :id",
                new MapSqlParameterSource("id", warehouse.getId()), Integer.class);
        return days == null ? DEFAULT_IMMINENT_DAYS : days;
    }

    private void owned(long userId, long productId) {
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();
    }

    private static int count(List<ExpiryList.Item> lots, Status status) {
        return (int) lots.stream().filter(i -> i.status() == status).count();
    }
}
