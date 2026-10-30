package com.ipgo.server.service;

import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.dto.response.ProductSettings;
import com.ipgo.server.dto.response.ReplenishmentNeeds;
import com.ipgo.server.entity.CalculationResult;
import com.ipgo.server.entity.CalculationRun;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.ReplenishmentSetting;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.CalculationResultRepository;
import com.ipgo.server.repository.CalculationRunRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.ReplenishmentSettingRepository;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.util.DisplayQuantities;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// D1 입고 필요 목록 (BE-11). 가장 최근에 성공한 계산 결과를 오늘 날짜로 다시 판정해 돌려준다.
@Service
public class DashboardService {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final WarehouseService warehouses;
    private final CalculationRunRepository runs;
    private final CalculationResultRepository results;
    private final ProductRepository products;
    private final ReplenishmentSettingRepository settings;
    private final UploadFileRepository uploads;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DashboardService(WarehouseService warehouses, CalculationRunRepository runs,
            CalculationResultRepository results, ProductRepository products,
            ReplenishmentSettingRepository settings, UploadFileRepository uploads, JdbcTemplate jdbc, Clock clock) {
        this.warehouses = warehouses;
        this.runs = runs;
        this.results = results;
        this.products = products;
        this.settings = settings;
        this.uploads = uploads;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReplenishmentNeeds needs(long userId, String sort) {
        Comparator<ReplenishmentNeeds.Item> order = order(sort);
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        Optional<Warehouse> warehouse = warehouses.findDefault(userId);
        if (warehouse.isEmpty()) return empty(today, null, null);

        Long warehouseId = warehouse.get().getId();
        Long latestUploadId = uploads.findFirstByWarehouseIdOrderByIdDesc(warehouseId).map(UploadFile::getId).orElse(null);
        // 가장 최근 계산이 실패했으면, 이전에 성공한 결과를 보여주면서 "갱신 전"으로 표시한다.
        OffsetDateTime lastFailedRunAt = runs.findFirstByWarehouseIdOrderByIdDesc(warehouseId)
                .filter(run -> CalculationRun.FAILED.equals(run.getStatus()))
                .map(run -> seoul(run.getCreatedAt())).orElse(null);
        Optional<CalculationRun> done = runs.findFirstByWarehouseIdAndStatusOrderByIdDesc(warehouseId, CalculationRun.DONE);
        if (done.isEmpty()) return empty(today, lastFailedRunAt, latestUploadId);

        CalculationRun run = done.get();
        List<CalculationResult> runResults = results.findByRunId(run.getId());
        List<Long> productIds = runResults.stream().map(CalculationResult::getProductId).toList();
        Map<Long, Product> byId = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, ReplenishmentSetting> overrides = settings.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(ReplenishmentSetting::getProductId, Function.identity()));
        ProductSettings defaults = ProductSettings.defaults(warehouse);
        Map<Long, LocalDate> mismatchDates = mismatchDates(run.getId());

        List<ReplenishmentNeeds.Item> items = new ArrayList<>();
        List<ReplenishmentNeeds.NotCalculable> notCalculable = new ArrayList<>();
        List<ReplenishmentNeeds.Excluded> excluded = new ArrayList<>();
        int notNeeded = 0;
        for (CalculationResult result : runResults) {
            Product product = byId.get(result.getProductId());
            // 셀러가 사용을 끈 상품은 계산 뒤에 껐더라도 바로 목록에서 뺀다.
            if (!product.isActive()) continue;
            Integer leadTime = defaults.applying(overrides.get(product.getId())).leadTimeDays();
            switch (NeedJudgment.needStatus(result, run.getAsOfDate(), today, leadTime)) {
                case NeedJudgment.NEED_NOW -> items.add(new ReplenishmentNeeds.Item(product.getId(), result.getId(),
                        product.getSkuBarcode(), product.getSkuName(), result.getRecommendedQty(),
                        DisplayQuantities.won(result.getRecommendedAmount()), result.getExpectedShortageOn(),
                        leadTime, result.getPreArrivalRisk()));
                case CalculationResult.EXCLUDED -> excluded.add(new ReplenishmentNeeds.Excluded(product.getId(),
                        product.getSkuName(), mismatchDates.get(product.getId()), result.getReason()));
                // 기준일이 오래돼 재업로드가 필요한 상품도 계산할 수 없는 목록에 사유와 함께 보여준다.
                case CalculationResult.NOT_CALCULABLE, CalculationResult.REUPLOAD_REQUIRED ->
                        notCalculable.add(new ReplenishmentNeeds.NotCalculable(product.getId(), product.getSkuName(),
                                result.getReason() != null ? result.getReason() : REUPLOAD_REASON));
                default -> notNeeded++;
            }
        }
        items.sort(order);
        return new ReplenishmentNeeds(run.getAsOfDate(), today, NeedJudgment.daysSince(run.getAsOfDate(), today),
                lastFailedRunAt == null ? "UP_TO_DATE" : "STALE", run.getId(), seoul(run.getCompletedAt()),
                lastFailedRunAt, latestUploadId,
                new ReplenishmentNeeds.Summary(items.size(), notNeeded, notCalculable.size(), excluded.size()),
                items, notCalculable, excluded);
    }

    // 이 계산에 쓴 업로드들의 검증 리포트에서 상품별 검산 불일치 날짜를 찾는다. 여러 파일에 있으면 최근 업로드 값을 쓴다.
    private Map<Long, LocalDate> mismatchDates(long runId) {
        List<Long> uploadIds = jdbc.queryForList(
                "SELECT upload_file_id FROM run_source_files WHERE run_id = ? ORDER BY upload_file_id", Long.class, runId);
        Map<Long, LocalDate> dates = new HashMap<>();
        for (UploadFile upload : uploads.findAllById(uploadIds).stream()
                .sorted(Comparator.comparing(UploadFile::getId)).toList()) {
            UploadProcessingReport report = upload.getValidationReport();
            if (report == null || report.validation() == null) continue;
            report.validation().excludedSkus().forEach(sku -> dates.put(sku.productId(), sku.date()));
        }
        return dates;
    }

    static final String REUPLOAD_REASON = "기준일이 7일 넘게 지났어요. 최신 수불부를 다시 올려 주세요";

    // shortage: 소진일 빠른 순 · risk: 품절 위험 높은 순 · amount: 금액 큰 순. 값이 없으면 뒤로 보낸다.
    private static Comparator<ReplenishmentNeeds.Item> order(String sort) {
        Comparator<ReplenishmentNeeds.Item> byProduct = Comparator.comparing(ReplenishmentNeeds.Item::productId);
        return switch (sort) {
            case "shortage" -> Comparator.comparing(ReplenishmentNeeds.Item::expectedShortageOn,
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(byProduct);
            case "risk" -> Comparator.comparing(ReplenishmentNeeds.Item::preArrivalRisk,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(byProduct);
            case "amount" -> Comparator.comparing(ReplenishmentNeeds.Item::recommendedAmount,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(byProduct);
            default -> throw ProductException.invalidInput();
        };
    }

    private static ReplenishmentNeeds empty(LocalDate today, OffsetDateTime lastFailedRunAt, Long latestUploadId) {
        return new ReplenishmentNeeds(null, today, null, lastFailedRunAt == null ? null : "STALE", null, null,
                lastFailedRunAt, latestUploadId, new ReplenishmentNeeds.Summary(0, 0, 0, 0),
                List.of(), List.of(), List.of());
    }

    private static OffsetDateTime seoul(Instant instant) {
        return instant == null ? null : instant.atZone(SEOUL).toOffsetDateTime();
    }
}
