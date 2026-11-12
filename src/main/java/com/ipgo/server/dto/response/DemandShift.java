package com.ipgo.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

// #193 판매 급변. 기준에 맞는 상품만 값이 있고, 아니면 응답에서 null이다.
// direction: UP(갑자기 많이 팔림) · DOWN(직전 계산의 급증이 끝나 권장량이 크게 줄어듦)
// recentDailyQty: 기준일까지 최근 구간의 하루 평균 출고, baselineDailyQty: 그 전 구간의 하루 평균 출고
// ratio: recent ÷ baseline(이전 평균이 0이면 null), since: 최근 구간 시작일
// previousRecommendedQty: DOWN일 때 직전 계산의 권장량(UP이면 null)
public record DemandShift(String direction, BigDecimal recentDailyQty, BigDecimal baselineDailyQty, BigDecimal ratio,
        LocalDate since, Long previousRecommendedQty) {
    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
}
