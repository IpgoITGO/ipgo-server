package com.ipgo.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// K1 SKU 상세. 계산 결과가 아직 없으면 needStatus · result · accuracy는 null이다.
public record ProductDetail(Product product, String needStatus, LocalDate asOfDate, LocalDate expectedShortageOn,
        Settings settings, Result result, Accuracy accuracy, List<Trend> trend) {

    // fulfillment는 상품이 들어가는 창고 이름이다.
    public record Product(Long productId, String skuBarcode, String skuName, String fulfillment) {
    }

    // source: PRODUCT(상품별 값) · DEFAULT(셀러가 저장한 기본값) · SUGGESTED / ESTIMATED(저장 전 제안값)
    public record Settings(Integer leadTimeDays, String leadTimeSource, Integer reviewCycleDays,
            String reviewCycleSource, Long orderUnitQty, Long unitCost) {
    }

    // requiredQtyBeforeRounding은 ERD에 저장 칸이 없어 계산 연동 때 정한다. 그 전까지 null이다.
    public record Result(Long resultId, String status, String reason, String method, String methodReason,
            BigDecimal multiplier, Integer horizonDays, Long forecastOutboundQty, Long startStockQty,
            boolean startStockEstimated, Long plannedReceiptsInHorizon, Long preArrivalUnmetQty,
            Long requiredQtyBeforeRounding, Long recommendedQty, Long recommendedAmount) {
    }

    // history는 백테스트 연동 전까지 비어 있다. range는 등급이 LOW일 때만 있다.
    public record Accuracy(String grade, boolean isReference, Integer validObservationDays,
            Integer coverSuccessCount, Integer coverTrialCount, BigDecimal forecastErrorRate,
            List<History> history, Range range, LocalDate firstInboundOn) {
    }

    public record History(LocalDate originDate, Long forecastQty, Long actualQty, boolean covered) {
    }

    public record Range(Long low, Long high) {
    }

    public record Trend(LocalDate date, Long closingQty, Long outboundQty, boolean isValidObservation) {
    }
}
