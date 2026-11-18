package com.ipgo.server.service;

import com.ipgo.server.entity.CalculationResult;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NeedJudgmentTests {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private CalculationResult result(String status, LocalDate shortage) {
        var result = mock(CalculationResult.class);
        when(result.getStatus()).thenReturn(status);
        when(result.getExpectedShortageOn()).thenReturn(shortage);
        return result;
    }
    @Test void unknownShortageOrLeadTimeIsNotAConfirmedNoNeed() {
        assertThat(NeedJudgment.needStatus(result("OK", null), TODAY, 5)).isEqualTo("NOT_CALCULABLE");
        assertThat(NeedJudgment.needStatus(result("OK", TODAY.plusDays(7)), TODAY, null)).isEqualTo("NOT_CALCULABLE");
    }
    @Test void leadTimeBoundaryIsInclusiveAndLaterShortageCanWait() {
        var result = result("OK", TODAY.plusDays(7));
        assertThat(NeedJudgment.needStatus(result, TODAY, 7)).isEqualTo("NEED_NOW");
        assertThat(NeedJudgment.needStatus(result, TODAY, 5)).isEqualTo("NOT_NEEDED");
    }
    @Test void explicitZeroRecommendationAndStoredReuploadKeepTheirMeaning() {
        assertThat(NeedJudgment.needStatus(result("NOT_NEEDED", null), TODAY, 5)).isEqualTo("NOT_NEEDED");
        // #261 엔진이 준 재업로드 필요 결과는 그대로 둔다.
        assertThat(NeedJudgment.needStatus(result("REUPLOAD_REQUIRED", null), TODAY, 5)).isEqualTo("REUPLOAD_REQUIRED");
    }
}
