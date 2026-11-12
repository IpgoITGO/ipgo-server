package com.ipgo.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// D1 입고 필요 목록. 계산 결과가 없으면 asOfDate · runId는 null이고 목록은 비어 있다.
public record ReplenishmentNeeds(LocalDate asOfDate, LocalDate today, Long daysSinceAsOf, String resultStatus,
        Long runId, OffsetDateTime resultComputedAt, OffsetDateTime lastFailedRunAt, Long latestUploadId,
        Summary summary, List<Item> items, List<NotNeededItem> notNeededItems, List<NotCalculable> notCalculable,
        List<Excluded> excluded) {

    public record Summary(int needCount, int notNeededCount, int notCalculableCount, int excludedCount) {
    }

    // demandShift(#193): 판매 급변 기준에 맞는 상품만 값이 있고, 아니면 null이다.
    public record Item(Long productId, Long resultId, String skuBarcode, String skuName, Long recommendedQty,
            Long recommendedAmount, LocalDate expectedShortageOn, Integer leadTimeDays, BigDecimal preArrivalRisk,
            DemandShift demandShift) {
    }

    // "아직 괜찮아요" 상품. 예상 소진일이 가까운 순이고, 커버 기간 안에 소진되지 않으면 expectedShortageOn이 null이다.
    public record NotNeededItem(Long productId, Long resultId, String skuBarcode, String skuName,
            LocalDate expectedShortageOn, Integer leadTimeDays, DemandShift demandShift) {
    }

    public record NotCalculable(Long productId, String skuName, String reason, DemandShift demandShift) {
    }

    // date는 수불부 검산이 어긋난 날이다. 계산에 쓴 업로드의 검증 리포트에 없으면 null이다.
    public record Excluded(Long productId, String skuName, LocalDate date, String reason) {
    }
}
