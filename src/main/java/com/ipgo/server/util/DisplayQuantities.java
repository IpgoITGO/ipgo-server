package com.ipgo.server.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 엔진은 재고 · 수요를 소수로 계산하고, 화면에는 정수로 보여준다.
// 미확정: 재고는 내림, 수요는 올림(엔진 쪽 제안). 팀 결정이 나면 이 클래스만 바꾼다.
public final class DisplayQuantities {
    private DisplayQuantities() { }

    // 시작 재고 · 잔여 재고 · 날짜별 재고
    public static Long stock(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    // 예상 출고량 · 예상 출고 범위 · 미충족 수요
    public static Long demand(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    // 금액은 원 단위 정수
    public static Long won(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
