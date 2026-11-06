package com.ipgo.server.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

// #119 내 입고량. source는 어디서 가져온 수량인지, calculationRunId는 그때 화면의 계산 번호다.
public record PlannedQuantityRequest(
        @NotNull @Positive Long quantity,
        @NotNull @Pattern(regexp = "SCENARIO_80|SCENARIO_100|SCENARIO_120|MANUAL") String source,
        @NotNull @Positive Long calculationRunId) {
}
