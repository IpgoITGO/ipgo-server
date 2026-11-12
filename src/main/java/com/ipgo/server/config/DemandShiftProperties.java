package com.ipgo.server.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// #193 판매 급변 기준. 첫 기준이라 application.yaml(ipgo.demand-shift.*)이나 환경변수로 바꿀 수 있다.
// - recentDays: 기준일까지 최근 며칠의 하루 평균을 볼지
// - baselineDays: 그 전 며칠과 비교할지
// - upRatio · upMinIncrease: 최근 평균이 이전 평균의 몇 배 이상, 그리고 하루 몇 개 이상 늘어야 UP인지
// - downRatio: 직전 UP 상품의 권장량이 직전의 몇 배 이하로 줄어야 DOWN인지
@ConfigurationProperties("ipgo.demand-shift")
public record DemandShiftProperties(@DefaultValue("7") int recentDays, @DefaultValue("28") int baselineDays,
        @DefaultValue("3") BigDecimal upRatio, @DefaultValue("10") BigDecimal upMinIncrease,
        @DefaultValue("0.5") BigDecimal downRatio) {
}
