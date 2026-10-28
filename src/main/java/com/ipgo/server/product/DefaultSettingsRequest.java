package com.ipgo.server.product;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// S2 요청. 기본값은 세 칸 모두 1 이상이어야 한다. recalculate는 계산 연동 때 쓴다.
public record DefaultSettingsRequest(
        @NotNull @Positive Integer leadTimeDays,
        @NotNull @Positive Integer reviewCycleDays,
        @NotNull @Positive Long orderUnitQty,
        Boolean recalculate) {
}
