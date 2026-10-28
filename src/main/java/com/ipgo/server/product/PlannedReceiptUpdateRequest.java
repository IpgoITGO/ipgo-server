package com.ipgo.server.product;

import java.time.LocalDate;

import jakarta.validation.constraints.Positive;

// R3 수정 요청. 보낸 칸만 바꾼다.
public record PlannedReceiptUpdateRequest(
        @Positive Long quantity,
        LocalDate expectedOn) {
}
