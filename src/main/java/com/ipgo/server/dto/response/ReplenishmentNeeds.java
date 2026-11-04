package com.ipgo.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// D1 입고 필요 목록. 계산 결과가 없으면 asOfDate · runId는 null이고 목록은 비어 있다.
public record ReplenishmentNeeds(LocalDate asOfDate, LocalDate today, Long daysSinceAsOf, String resultStatus,
        Long runId, OffsetDateTime resultComputedAt, OffsetDateTime lastFailedRunAt, Long latestUploadId,
        Summary summary, List<Item> items, List<NotCalculable> notCalculable, List<Excluded> excluded) {

    public record Summary(int needCount, int notNeededCount, int notCalculableCount, int excludedCount) {
    }

    public record Item(Long productId, Long resultId, String skuBarcode, String skuName, Long recommendedQty,
            Long recommendedAmount, LocalDate expectedShortageOn, Integer leadTimeDays, BigDecimal preArrivalRisk) {
    }

    public record NotCalculable(Long productId, String skuName, String reason) {
    }

    // date는 수불부 검산이 어긋난 날이다. 계산에 쓴 업로드의 검증 리포트에 없으면 null이다.
    public record Excluded(Long productId, String skuName, LocalDate date, String reason) {
    }
}
