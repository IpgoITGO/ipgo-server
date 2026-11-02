package com.ipgo.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// K2 시나리오 비교표. 권장량이 0개거나 계산하지 못한 결과는 scenarios가 비어 있다.
// riskStatus: OK · LOW_GRADE(정확도 낮아 위험 미산출) · INSUFFICIENT_SAMPLE(표본 부족). 정상 결과가 아니면 null이다.
public record ScenarioComparison(Long resultId, boolean isEstimate, BigDecimal preArrivalRisk, Integer riskSampleSize,
        String riskBasis, String riskStatus, List<Scenario> scenarios) {

    public record Scenario(Long scenarioId, String type, Long qty, boolean isDuplicate, LocalDate expectedDepletionOn,
            Long expectedEndingStock, Long unmetDemandQty, BigDecimal postArrivalRisk) {
    }
}
