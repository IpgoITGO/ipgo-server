package com.ipgo.server.product;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

// S3 요청. 입고 조건 칸을 비우면 기본값을 따른다. 원가는 보낸 경우에만 바꾼다.
public record ProductSettingsRequest(
        @Positive Integer leadTimeDays,
        @Positive Integer reviewCycleDays,
        @Positive Long orderUnitQty,
        @PositiveOrZero Long unitCost) {
}
