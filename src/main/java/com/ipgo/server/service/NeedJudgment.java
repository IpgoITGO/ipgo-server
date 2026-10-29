package com.ipgo.server.service;

import com.ipgo.server.entity.CalculationResult;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// 대시보드(D1)와 SKU 상세(K1)가 같은 기준으로 "지금 넣어야 하는지"를 판정한다 (명세서 3.5 · 3.6).
// 저장된 결과는 계산할 때의 판정이라, 화면을 열 때마다 오늘 날짜로 다시 맞춘다.
final class NeedJudgment {
    static final String NEED_NOW = "NEED_NOW";
    // 기준일이 이보다 오래되면 계산 결과를 믿지 않고 재업로드를 안내한다 (기획서 N = 7일).
    static final int MAX_DAYS_SINCE_AS_OF = 7;

    private NeedJudgment() { }

    static long daysSince(LocalDate asOfDate, LocalDate today) {
        return ChronoUnit.DAYS.between(asOfDate, today);
    }

    static String needStatus(CalculationResult result, LocalDate asOfDate, LocalDate today, Integer leadTimeDays) {
        String status = result.getStatus();
        if (CalculationResult.NOT_CALCULABLE.equals(status) || CalculationResult.EXCLUDED.equals(status)
                || CalculationResult.REUPLOAD_REQUIRED.equals(status)) {
            return status;
        }
        if (daysSince(asOfDate, today) > MAX_DAYS_SINCE_AS_OF) return CalculationResult.REUPLOAD_REQUIRED;
        // 예상 소진일이 오늘 + 리드타임 안이면 지금 넣어야 한다.
        LocalDate shortage = result.getExpectedShortageOn();
        int leadTime = leadTimeDays == null ? 0 : leadTimeDays;
        if (shortage != null && !shortage.isAfter(today.plusDays(leadTime))) return NEED_NOW;
        return CalculationResult.NOT_NEEDED;
    }
}
