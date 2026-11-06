package com.ipgo.server.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

// #111 창고 보관 요율(원 / CBM · 월). null이면 요율을 지운다.
public record StorageCostRequest(@Positive @Digits(integer = 10, fraction = 2) BigDecimal ratePerCbm) {
}
