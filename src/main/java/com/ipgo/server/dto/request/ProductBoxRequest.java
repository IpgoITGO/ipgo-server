package com.ipgo.server.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

// #111 상품 상자 1개 크기(cm). 세 값 모두 0보다 커야 하고 소수 첫째 자리까지 받는다.
public record ProductBoxRequest(
        @NotNull @Positive @Digits(integer = 6, fraction = 1) BigDecimal width,
        @NotNull @Positive @Digits(integer = 6, fraction = 1) BigDecimal depth,
        @NotNull @Positive @Digits(integer = 6, fraction = 1) BigDecimal height) {
}
