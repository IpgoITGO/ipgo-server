package com.ipgo.server.product;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// R2 요청. 도착 예정일은 없어도 저장하지만 계산에는 반영하지 않는다(FE-11).
public record PlannedReceiptRequest(
        @NotNull Long productId,
        @NotNull @Positive Long quantity,
        LocalDate expectedOn) {
}
