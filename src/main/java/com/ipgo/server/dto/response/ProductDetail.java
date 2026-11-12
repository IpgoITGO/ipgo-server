package com.ipgo.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// K1 SKU 상세. 계산 결과가 아직 없으면 needStatus · result · accuracy는 null이다.
public record ProductDetail(Product product, String needStatus, LocalDate asOfDate, LocalDate expectedShortageOn,
        Settings settings, Result result, Accuracy accuracy, List<Trend> trend, DemandShift demandShift) {

    // fulfillment는 상품이 들어가는 창고 이름이다.
    public record Product(Long productId, String skuBarcode, String skuName, String fulfillment) {
    }

    // source: PRODUCT(상품별 값) · DEFAULT(셀러가 저장한 기본값) · SUGGESTED / ESTIMATED(저장 전 제안값)
    public record Settings(Integer leadTimeDays, String leadTimeSource, Integer reviewCycleDays,
            String reviewCycleSource, Long orderUnitQty, Long unitCost) {
    }

    // requiredQtyBeforeRounding은 계산 run의 resultFacts에 저장된 원본 필요량에서 읽는다.
    public record Result(Long resultId, String status, String reason, String method, String methodReason,
            BigDecimal multiplier, Integer horizonDays, Long forecastOutboundQty, Long startStockQty,
            boolean startStockEstimated, Long plannedReceiptsInHorizon, Long preArrivalUnmetQty,
            Long requiredQtyBeforeRounding, Long recommendedQty, Long recommendedAmount) {
    }

    // history는 같은 계산의 TUNE 재생 이력이다. 구버전 결과는 빈 목록, evaluation=null.
    public record Accuracy(String grade, boolean isReference, Integer validObservationDays,
            Integer coverSuccessCount, Integer coverTrialCount, BigDecimal forecastErrorRate,
            List<History> history, Range range, LocalDate firstInboundOn, Evaluation evaluation) {
    }

    public record Evaluation(String status, String reason, String source, String period,
            LocalDate periodStart, LocalDate periodEnd, LocalDate observedThrough, Integer horizonDays) { }

    public record History(LocalDate originDate, Long forecastQty, Long actualQty, boolean covered) {
    }

    public record Range(Long low, Long high) {
    }

    // lastYearOutboundQty: 작년 같은 월·일(2/29는 2/28)의 출고. 계산식 A의 "작년 같은 기간"을 화면에 겹쳐 그리는 용도(#158).
    // 작년 기록이 없으면 null이다.
    public record Trend(LocalDate date, Long closingQty, Long outboundQty, boolean isValidObservation,
            Long lastYearOutboundQty) {
    }
}
