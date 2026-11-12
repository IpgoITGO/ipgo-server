package com.ipgo.server.service;

import com.ipgo.server.dto.response.DemandShift;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// K1 SKU 상세 (BE-11 · BE-12). 상품 · 입고 조건 · 가장 최근에 성공한 계산 결과 · 정확도를 모은다.
// 정확도 이력은 선택한 계산 run의 원본 facts에서만 읽는다.
@Service
public class ProductDetailService {
    // from · to를 주지 않으면 마지막 수불 날짜까지 90일을 보여준다.
    static final int DEFAULT_TREND_DAYS = 90;

    private final ProductRepository products;
    private final WarehouseService warehouses;
    private final ReviewCycleSuggestionService suggestions;
    private final ReplenishmentSettingRepository settings;
    private final CalculationRunRepository runs;
    private final CalculationResultRepository results;
    private final InventoryUnitRepository inventoryUnits;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final tools.jackson.databind.json.JsonMapper mapper;
    private final DemandShiftService demandShifts;

    public ProductDetailService(ProductRepository products, WarehouseService warehouses,
            ReplenishmentSettingRepository settings, CalculationRunRepository runs,
            CalculationResultRepository results, InventoryUnitRepository inventoryUnits, JdbcTemplate jdbc,
            Clock clock, tools.jackson.databind.json.JsonMapper mapper, ReviewCycleSuggestionService suggestions,
            DemandShiftService demandShifts) {
        this.products = products;
        this.demandShifts = demandShifts;
        this.warehouses = warehouses;
        this.suggestions = suggestions;
        this.settings = settings;
        this.runs = runs;
        this.results = results;
        this.inventoryUnits = inventoryUnits;
        this.jdbc = jdbc;
        this.clock = clock;
        this.mapper = mapper;
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
        // #193 대시보드와 같은 계산 결과 기준의 판매 급변
        DemandShift demandShift = run.map(r -> demandShifts.forRun(r).get(productId)).orElse(null);
        if (result.isEmpty()) {
            return new ProductDetail(summary, null, run.map(CalculationRun::getAsOfDate).orElse(null), null,
                    productSettings, null, null, trend, demandShift);
        }

        CalculationResult calculated = result.get();
        LocalDate asOfDate = run.get().getAsOfDate();
        LocalDate today = LocalDate.now(clock.withZone(DashboardService.SEOUL));
        String needStatus = NeedJudgment.needStatus(calculated, asOfDate, today, productSettings.leadTimeDays());
        JsonNode facts = resultFacts(calculated);
        return new ProductDetail(summary, needStatus, asOfDate, calculated.getExpectedShortageOn(), productSettings,
                result(calculated, needStatus, facts), accuracy(calculated, firstInboundOn, facts), trend, demandShift);
    }

    // 정제가 끝난(DONE) 업로드의 상품 일별 수불. 같은 날짜가 덮어쓰기로 여러 번 올라왔으면 가장 최근 업로드를 쓴다.
    // 날짜마다 작년 같은 월·일의 출고를 같이 붙인다(#158). 작년 기록이 없으면 null이다.
    private List<ProductDetail.Trend> trend(long productId, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : jdbc.queryForObject("""
                SELECT MAX(s.stock_date) FROM product_daily_stocks s
                JOIN upload_files u ON u.id = s.upload_file_id
                WHERE s.product_id = ? AND u.status = 'DONE'
                """, LocalDate.class, productId);
        if (end == null) return List.of();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_TREND_DAYS - 1);
        Map<LocalDate, Long> lastYearOutbound = new HashMap<>();
        for (DailyStock day : dailyStocks(productId, lastYearOf(start), lastYearOf(end))) {
            lastYearOutbound.put(day.date(), DisplayQuantities.demand(day.outbound()));
        }
        return dailyStocks(productId, start, end).stream().map(day -> new ProductDetail.Trend(day.date(),
                DisplayQuantities.stock(day.closing()), DisplayQuantities.demand(day.outbound()), day.valid(),
                lastYearOutbound.get(lastYearOf(day.date())))).toList();
    }

    private record DailyStock(LocalDate date, BigDecimal closing, BigDecimal outbound, boolean valid) { }

    private List<DailyStock> dailyStocks(long productId, LocalDate start, LocalDate end) {
        return jdbc.query("""
                SELECT s.stock_date, s.closing_qty, s.outbound_qty, s.is_valid_observation
                FROM product_daily_stocks s
                WHERE s.product_id = ? AND s.stock_date BETWEEN ? AND ?
                  AND s.upload_file_id = (
                      SELECT MAX(l.upload_file_id) FROM product_daily_stocks l
                      JOIN upload_files u ON u.id = l.upload_file_id
                      WHERE l.product_id = s.product_id AND l.stock_date = s.stock_date AND u.status = 'DONE')
                ORDER BY s.stock_date
                """, (rs, i) -> new DailyStock(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getBoolean(4)), productId, start, end);
    }

    // 엔진 계산식 A와 같은 "작년 같은 날": 같은 월·일. minusYears는 2/29를 작년 2/28로 맞춘다.
    public static LocalDate lastYearOf(LocalDate day) {
        return day.minusYears(1);
    }

    private ProductDetail.Settings settings(Product product, Optional<Warehouse> warehouse,
            ReplenishmentSetting override) {
        ProductSettings applied = suggestions.defaults(warehouse).applying(override);
        boolean confirmed = warehouse.map(Warehouse::hasConfirmedDefaults).orElse(false);
        String fallback = confirmed ? "DEFAULT" : "SUGGESTED";
        String leadTimeSource = override != null && override.getLeadTimeDays() != null ? "PRODUCT" : fallback;
        String reviewCycleSource = override != null && override.getReviewCycleDays() != null ? "PRODUCT"
                : confirmed ? "DEFAULT" : "ESTIMATED";
        return new ProductDetail.Settings(applied.leadTimeDays(), leadTimeSource, applied.reviewCycleDays(),
                reviewCycleSource, applied.orderUnitQty(), DisplayQuantities.won(product.getUnitCost()));
    }

    private ProductDetail.Result result(CalculationResult result, String needStatus, JsonNode facts) {
        return new ProductDetail.Result(result.getId(), result.getStatus(), result.getReason() != null ? result.getReason()
                        : CalculationResult.NOT_CALCULABLE.equals(needStatus) && CalculationResult.OK.equals(result.getStatus())
                                ? NeedJudgment.UNKNOWN_NEED_REASON : null, result.getMethod(),
                result.getMethodReason(), result.getMultiplier(), result.getHorizonDays(),
                DisplayQuantities.demand(result.getForecastOutboundQty()),
                DisplayQuantities.stock(result.getStartStockQty()),
                // 계산일이 기준일보다 늦으면 시작 재고는 예상 출고로 이어서 추정한 값이다.
                result.getStartDate().isAfter(result.getOriginDate()),
                result.getPlannedReceiptQty(), DisplayQuantities.demand(result.getPreArrivalUnmetQty()), beforeRounding(facts),
                result.getRecommendedQty(), DisplayQuantities.won(result.getRecommendedAmount()));
    }

    private JsonNode resultFacts(CalculationResult result) {
        String raw = jdbc.queryForObject("SELECT selection_snapshot FROM calculation_runs WHERE id = ?", String.class, result.getRunId());
        return EngineJobs.readDatabaseJson(mapper, raw).path("resultFacts").path("" + result.getProductId());
    }

    private Long beforeRounding(JsonNode facts) {
        var value = facts.path("requiredQtyBeforeRounding");
        return value.isNumber() ? DisplayQuantities.demand(value.decimalValue()) : null;
    }

    private ProductDetail.Accuracy accuracy(CalculationResult result, LocalDate firstInboundOn, JsonNode resultFacts) {
        boolean low = "LOW".equals(result.getAccuracyGrade());
        ProductDetail.Range range = low && result.getForecastRangeLow() != null
                ? new ProductDetail.Range(DisplayQuantities.demand(result.getForecastRangeLow()),
                        DisplayQuantities.demand(result.getForecastRangeHigh()))
                : null;
        var facts = resultFacts.path("accuracyEvaluation");
        var history = new java.util.ArrayList<ProductDetail.History>();
        ProductDetail.Evaluation evaluation = null;
        if (!facts.isMissingNode() && !facts.isNull()) {
            for (var point : facts.path("history")) history.add(new ProductDetail.History(
                    LocalDate.parse(point.path("originDate").asText()),
                    DisplayQuantities.demand(point.path("forecastQty").decimalValue()),
                    DisplayQuantities.demand(point.path("actualQty").decimalValue()), point.path("covered").asBoolean()));
            evaluation = new ProductDetail.Evaluation(facts.path("status").asText(), facts.path("reason").isString() ? facts.path("reason").asText() : null,
                    "HISTORICAL_REPLAY", "TUNE", optionalDate(facts,"periodStart"), optionalDate(facts,"periodEnd"),
                    optionalDate(facts,"observedThrough"), facts.path("horizonDays").isIntegralNumber() ? facts.path("horizonDays").asInt() : null);
        }
        return new ProductDetail.Accuracy(result.getAccuracyGrade(), low, result.getValidObservationDays(),
                result.getCoverSuccessCount(), result.getCoverTrialCount(), result.getForecastErrorRate(),
                List.copyOf(history), range, firstInboundOn, evaluation);
    }
    private static LocalDate optionalDate(tools.jackson.databind.JsonNode node, String key) {
        return node.path(key).isString() ? LocalDate.parse(node.path(key).asText()) : null;
    }
}
