package com.ipgo.server.service;

import com.ipgo.server.entity.CalculationResult;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// 대시보드(D1)와 SKU 상세(K1)가 같은 기준으로 "지금 넣어야 하는지"를 판정한다 (명세서 3.5 · 3.6).
// 저장된 결과는 계산할 때의 판정이라, 화면을 열 때마다 오늘 날짜로 다시 맞춘다.
final class NeedJudgment {
    static final String NEED_NOW = "NEED_NOW";
    static final String UNKNOWN_NEED_REASON = "입고 판정에 필요한 소진 예상일 또는 리드타임을 아직 확인하지 못했어요";
    // 경고 기준 (기획서 N = 7일). 기준일이 이보다 오래되면 화면은 지난 결과와 함께 "새 수불부가 필요해요"를 띄운다(#261).
    // D1 · K1 판정은 바꾸지 않고, 하루 한 번 알림(DailyPushService)의 입고 필요 개수와 직접 수량 시뮬레이션만 이 기준으로 막는다.
    static final int MAX_DAYS_SINCE_AS_OF = 7;

    private NeedJudgment() { }

    static long daysSince(LocalDate asOfDate, LocalDate today) {
        return ChronoUnit.DAYS.between(asOfDate, today);
    }

    // 기준일이 7일 넘게 지나도 저장된 결과로 판정한다(#261). 오래됐는지는 daysSinceAsOf로 화면이 알린다.
    static String needStatus(CalculationResult result, LocalDate today, Integer leadTimeDays) {
        String status = result.getStatus();
        if (CalculationResult.NOT_CALCULABLE.equals(status) || CalculationResult.EXCLUDED.equals(status)
                || CalculationResult.REUPLOAD_REQUIRED.equals(status)) {
            return status;
        }
        // 예상 소진일이 오늘 + 리드타임 안이면 지금 넣어야 한다.
        LocalDate shortage = result.getExpectedShortageOn();
        if (CalculationResult.OK.equals(status) && (shortage == null || leadTimeDays == null))
            return CalculationResult.NOT_CALCULABLE;
        int leadTime = leadTimeDays == null ? 0 : leadTimeDays;
        if (shortage != null && !shortage.isAfter(today.plusDays(leadTime))) return NEED_NOW;
        return CalculationResult.NOT_NEEDED;
    }
}
