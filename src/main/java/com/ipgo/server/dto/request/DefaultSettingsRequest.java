package com.ipgo.server.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

// S2 요청. 기본값은 세 칸 모두 1 이상이어야 한다. recalculate는 계산 연동 때 쓴다.
public record DefaultSettingsRequest(
        @NotNull @Positive Integer leadTimeDays,
        @NotNull @Positive Integer reviewCycleDays,
        @NotNull @Positive Long orderUnitQty,
        Boolean recalculate,
        List<@NotNull @Valid ProductOverride> productOverrides) {

    public DefaultSettingsRequest(Integer leadTimeDays, Integer reviewCycleDays, Long orderUnitQty, Boolean recalculate) {
        this(leadTimeDays, reviewCycleDays, orderUnitQty, recalculate, List.of());
    }

    public record ProductOverride(
            @NotNull @Positive Long productId,
            @Positive Integer leadTimeDays,
            @Positive Integer reviewCycleDays,
            @Positive Long orderUnitQty,
            @PositiveOrZero Long unitCost) {
    }
}
