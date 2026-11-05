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

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// C2 계산 상태 (BE-16)와 C3 계산 멈추기 (#106). 계산을 시작하고 저장하는 쪽은 EngineCalculationService · EngineResultWriter다.
@Service
public class CalculationRunService {
    private final CalculationRunRepository runs;
    private final CalculationResultRepository results;
    private final WarehouseRepository warehouses;
    private final ProductRepository products;
    private final ReplenishmentSettingRepository settings;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    public static final String CANCELED_MESSAGE = "계산을 멈췄어요. 이전 결과는 그대로예요.";

    public CalculationRunService(CalculationRunRepository runs, CalculationResultRepository results,
            WarehouseRepository warehouses, ProductRepository products, ReplenishmentSettingRepository settings,
            Clock clock, JdbcTemplate jdbc, EntityManager entities) {
        this.runs = runs;
        this.results = results;
        this.warehouses = warehouses;
        this.products = products;
        this.settings = settings;
        this.clock = clock;
        this.jdbc = jdbc;
        this.entities = entities;
    }

    @Transactional(readOnly = true)
    public CalculationRunStatus status(long userId, long runId) {
        CalculationRun run = runs.findById(runId).orElseThrow(ProductException::notFound);
        Warehouse warehouse = warehouses.findById(run.getWarehouseId()).orElseThrow(ProductException::notFound);
        if (!warehouse.getUserId().equals(userId)) throw ProductException.forbidden();
        return toStatus(run, warehouse);
    }

    // C3 계산 멈추기 (#106). 엔진 결과 처리와 같은 순서(창고 → 작업 → 계산)로 잠가서 결과 저장과 겹치지 않게 한다.
    // 엔진 작업도 FAILED로 끝내므로 다시 보내지 않고, 뒤늦게 온 결과는 EngineResults가 끝난 작업으로 보고 버린다.
    @Transactional
    public CalculationRunStatus cancel(long userId, long runId) {
        CalculationRun run = runs.findById(runId).orElseThrow(ProductException::notFound);
        Warehouse warehouse = warehouses.findByIdForUpdate(run.getWarehouseId()).orElseThrow(ProductException::notFound);
        if (!warehouse.getUserId().equals(userId)) throw ProductException.forbidden();
        String jobId = jdbc.queryForObject("SELECT job_id FROM calculation_runs WHERE id = ?", String.class, runId);
        if (jobId != null) jdbc.queryForList("SELECT job_id FROM engine_jobs WHERE job_id = ? FOR UPDATE", String.class, jobId);
        int changed = jdbc.update("""
                UPDATE calculation_runs SET status = 'FAILED', error_message = ?, completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('QUEUED','RUNNING')
                """, CANCELED_MESSAGE, runId);
        if (changed == 0) throw ProductException.calculationFinished();
        if (jobId != null) jdbc.update("""
                UPDATE engine_jobs SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND status IN ('PENDING','SENT')
                """, jobId);
        entities.refresh(run);
        return toStatus(run, warehouse);
    }

    private CalculationRunStatus toStatus(CalculationRun run, Warehouse warehouse) {
        boolean done = CalculationRun.DONE.equals(run.getStatus());
        return new CalculationRunStatus(run.getId(), run.getStatus(), run.getAsOfDate(), run.getEngineVersion(), "B-1.00".equals(run.getPolicyVersion())
                        ? new CalculationRunStatus.Policy("B", new java.math.BigDecimal("1.00")) : null,
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
