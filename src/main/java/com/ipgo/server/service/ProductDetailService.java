package com.ipgo.server.service;

import com.ipgo.server.dto.response.ProductDetail;
import com.ipgo.server.dto.response.ProductSettings;
import com.ipgo.server.entity.CalculationResult;
import com.ipgo.server.entity.CalculationRun;
import com.ipgo.server.entity.InventoryUnit;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.ReplenishmentSetting;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.CalculationResultRepository;
import com.ipgo.server.repository.CalculationRunRepository;
import com.ipgo.server.repository.InventoryUnitRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.ReplenishmentSettingRepository;
import com.ipgo.server.util.DisplayQuantities;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// K1 SKU 상세 (BE-11 · BE-12). 상품 · 입고 조건 · 가장 최근에 성공한 계산 결과 · 정확도를 모은다.
// 정확도 이력(history)은 백테스트 연동 뒤에 채운다.
@Service
public class ProductDetailService {
    // from · to를 주지 않으면 마지막 수불 날짜까지 90일을 보여준다.
    static final int DEFAULT_TREND_DAYS = 90;

    private final ProductRepository products;
    private final WarehouseService warehouses;
    private final ReplenishmentSettingRepository settings;
    private final CalculationRunRepository runs;
    private final CalculationResultRepository results;
    private final InventoryUnitRepository inventoryUnits;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProductDetailService(ProductRepository products, WarehouseService warehouses,
            ReplenishmentSettingRepository settings, CalculationRunRepository runs,
            CalculationResultRepository results, InventoryUnitRepository inventoryUnits, JdbcTemplate jdbc,
            Clock clock) {
        this.products = products;
        this.warehouses = warehouses;
        this.settings = settings;
        this.runs = runs;
        this.results = results;
        this.inventoryUnits = inventoryUnits;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(long userId, long productId, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) throw ProductException.invalidInput();
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();

        Optional<Warehouse> warehouse = warehouses.findDefault(userId);
        ReplenishmentSetting override = settings.findById(productId).orElse(null);
        ProductDetail.Settings productSettings = settings(product, warehouse, override);

        Optional<CalculationRun> run = warehouse.flatMap(
                w -> runs.findFirstByWarehouseIdAndStatusOrderByIdDesc(w.getId(), CalculationRun.DONE));
        Optional<CalculationResult> result = run.flatMap(r -> results.findByRunIdAndProductId(r.getId(), productId));
        LocalDate firstInboundOn = inventoryUnits.findFirstByProductIdOrderByReceivedOnAsc(productId)
                .map(InventoryUnit::getReceivedOn).orElse(null);
        var summary = new ProductDetail.Product(product.getId(), product.getSkuBarcode(), product.getSkuName(),
                warehouse.map(Warehouse::getName).orElse(null));
        List<ProductDetail.Trend> trend = trend(productId, from, to);
        if (result.isEmpty()) {
            return new ProductDetail(summary, null, run.map(CalculationRun::getAsOfDate).orElse(null), null,
                    productSettings, null, null, trend);
        }

        CalculationResult calculated = result.get();
        LocalDate asOfDate = run.get().getAsOfDate();
        LocalDate today = LocalDate.now(clock.withZone(DashboardService.SEOUL));
        String needStatus = NeedJudgment.needStatus(calculated, asOfDate, today, productSettings.leadTimeDays());
        return new ProductDetail(summary, needStatus, asOfDate, calculated.getExpectedShortageOn(), productSettings,
                result(calculated), accuracy(calculated, firstInboundOn), trend);
    }

    // 정제가 끝난(DONE) 업로드의 상품 일별 수불. 같은 날짜가 덮어쓰기로 여러 번 올라왔으면 가장 최근 업로드를 쓴다.
    private List<ProductDetail.Trend> trend(long productId, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : jdbc.queryForObject("""
                SELECT MAX(s.stock_date) FROM product_daily_stocks s
                JOIN upload_files u ON u.id = s.upload_file_id
                WHERE s.product_id = ? AND u.status = 'DONE'
                """, LocalDate.class, productId);
        if (end == null) return List.of();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_TREND_DAYS - 1);
        return jdbc.query("""
                SELECT s.stock_date, s.closing_qty, s.outbound_qty, s.is_valid_observation
                FROM product_daily_stocks s
                WHERE s.product_id = ? AND s.stock_date BETWEEN ? AND ?
                  AND s.upload_file_id = (
                      SELECT MAX(l.upload_file_id) FROM product_daily_stocks l
                      JOIN upload_files u ON u.id = l.upload_file_id
                      WHERE l.product_id = s.product_id AND l.stock_date = s.stock_date AND u.status = 'DONE')
                ORDER BY s.stock_date
                """, (rs, i) -> new ProductDetail.Trend(rs.getObject(1, LocalDate.class),
                        DisplayQuantities.stock(rs.getBigDecimal(2)), DisplayQuantities.demand(rs.getBigDecimal(3)),
                        rs.getBoolean(4)), productId, start, end);
    }

    private static ProductDetail.Settings settings(Product product, Optional<Warehouse> warehouse,
            ReplenishmentSetting override) {
        ProductSettings applied = ProductSettings.defaults(warehouse).applying(override);
        boolean confirmed = warehouse.map(Warehouse::hasConfirmedDefaults).orElse(false);
        String fallback = confirmed ? "DEFAULT" : "SUGGESTED";
        String leadTimeSource = override != null && override.getLeadTimeDays() != null ? "PRODUCT" : fallback;
        String reviewCycleSource = override != null && override.getReviewCycleDays() != null ? "PRODUCT"
                : confirmed ? "DEFAULT" : "ESTIMATED";
        return new ProductDetail.Settings(applied.leadTimeDays(), leadTimeSource, applied.reviewCycleDays(),
                reviewCycleSource, applied.orderUnitQty(), DisplayQuantities.won(product.getUnitCost()));
    }

    private static ProductDetail.Result result(CalculationResult result) {
        return new ProductDetail.Result(result.getId(), result.getStatus(), result.getReason(), result.getMethod(),
                result.getMethodReason(), result.getMultiplier(), result.getHorizonDays(),
                DisplayQuantities.demand(result.getForecastOutboundQty()),
                DisplayQuantities.stock(result.getStartStockQty()),
                // 계산일이 기준일보다 늦으면 시작 재고는 예상 출고로 이어서 추정한 값이다.
                result.getStartDate().isAfter(result.getOriginDate()),
                result.getPlannedReceiptQty(), DisplayQuantities.demand(result.getPreArrivalUnmetQty()), null,
                result.getRecommendedQty(), DisplayQuantities.won(result.getRecommendedAmount()));
    }

    private static ProductDetail.Accuracy accuracy(CalculationResult result, LocalDate firstInboundOn) {
        boolean low = "LOW".equals(result.getAccuracyGrade());
        ProductDetail.Range range = low && result.getForecastRangeLow() != null
                ? new ProductDetail.Range(DisplayQuantities.demand(result.getForecastRangeLow()),
                        DisplayQuantities.demand(result.getForecastRangeHigh()))
                : null;
        return new ProductDetail.Accuracy(result.getAccuracyGrade(), low, result.getValidObservationDays(),
                result.getCoverSuccessCount(), result.getCoverTrialCount(), result.getForecastErrorRate(),
                List.of(), range, firstInboundOn);
    }
}
