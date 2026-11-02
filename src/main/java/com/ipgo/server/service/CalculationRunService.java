package com.ipgo.server.service;

import com.ipgo.server.dto.response.CalculationRunStatus;
import com.ipgo.server.dto.response.ProductSettings;
import com.ipgo.server.dto.response.ReplenishmentNeeds;
import com.ipgo.server.entity.CalculationResult;
import com.ipgo.server.entity.CalculationRun;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.ReplenishmentSetting;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.CalculationResultRepository;
import com.ipgo.server.repository.CalculationRunRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.ReplenishmentSettingRepository;
import com.ipgo.server.repository.WarehouseRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// C2 계산 상태 (BE-16). 계산을 시작하고 저장하는 쪽은 #27에서 만들고, 여기서는 읽기만 한다.
@Service
public class CalculationRunService {
    private final CalculationRunRepository runs;
    private final CalculationResultRepository results;
    private final WarehouseRepository warehouses;
    private final ProductRepository products;
    private final ReplenishmentSettingRepository settings;
    private final Clock clock;

    public CalculationRunService(CalculationRunRepository runs, CalculationResultRepository results,
            WarehouseRepository warehouses, ProductRepository products, ReplenishmentSettingRepository settings,
            Clock clock) {
        this.runs = runs;
        this.results = results;
        this.warehouses = warehouses;
        this.products = products;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CalculationRunStatus status(long userId, long runId) {
        CalculationRun run = runs.findById(runId).orElseThrow(ProductException::notFound);
        Warehouse warehouse = warehouses.findById(run.getWarehouseId()).orElseThrow(ProductException::notFound);
        if (!warehouse.getUserId().equals(userId)) throw ProductException.forbidden();

        boolean done = CalculationRun.DONE.equals(run.getStatus());
        return new CalculationRunStatus(run.getId(), run.getStatus(), run.getAsOfDate(), run.getEngineVersion(), null,
                CalculationRun.FAILED.equals(run.getStatus()) ? run.getErrorMessage() : null,
                seoul(run.getCreatedAt()), seoul(run.getCompletedAt()), done ? summary(run, warehouse) : null);
    }

    // 완료 팝업의 "지금 넣어야 할 상품 수". D1 대시보드와 같은 기준으로 오늘 날짜에 다시 판정한다.
    private ReplenishmentNeeds.Summary summary(CalculationRun run, Warehouse warehouse) {
        List<CalculationResult> runResults = results.findByRunId(run.getId());
        List<Long> productIds = runResults.stream().map(CalculationResult::getProductId).toList();
        Map<Long, Product> byId = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, ReplenishmentSetting> overrides = settings.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(ReplenishmentSetting::getProductId, Function.identity()));
        ProductSettings defaults = ProductSettings.defaults(Optional.of(warehouse));
        LocalDate today = LocalDate.now(clock.withZone(DashboardService.SEOUL));

        int need = 0, notNeeded = 0, notCalculable = 0, excluded = 0;
        for (CalculationResult result : runResults) {
            // 셀러가 사용을 끈 상품은 D1처럼 개수에서 뺀다.
            if (!byId.get(result.getProductId()).isActive()) continue;
            Integer leadTime = defaults.applying(overrides.get(result.getProductId())).leadTimeDays();
            switch (NeedJudgment.needStatus(result, run.getAsOfDate(), today, leadTime)) {
                case NeedJudgment.NEED_NOW -> need++;
                case CalculationResult.EXCLUDED -> excluded++;
                case CalculationResult.NOT_CALCULABLE, CalculationResult.REUPLOAD_REQUIRED -> notCalculable++;
                default -> notNeeded++;
            }
        }
        return new ReplenishmentNeeds.Summary(need, notNeeded, notCalculable, excluded);
    }

    private static OffsetDateTime seoul(Instant instant) {
        return instant == null ? null : instant.atZone(DashboardService.SEOUL).toOffsetDateTime();
    }
}
