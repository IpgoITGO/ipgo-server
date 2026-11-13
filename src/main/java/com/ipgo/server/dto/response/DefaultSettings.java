package com.ipgo.server.dto.response;

import com.ipgo.server.entity.Warehouse;

import java.time.LocalDate;
import java.util.Optional;

// S1 응답. confirmed가 false면 제안값과 ESTIMATED를, true면 저장한 기본값과 DEFAULT를 돌려준다.
// reviewCycleBasis는 입고 주기를 추정했을 때만 있다: 본 기간(from~to)과 센 상품 수(#166). 그 밖에는 null.
// reviewCycleSuggestion은 확인 여부와 관계없이 지금 수불부로 추정한 입고 주기와 근거다(#214).
// 셀러가 기본값을 바꾼 뒤에도 "추천값으로 되돌리기"를 할 수 있게 한다. 추정할 수 없으면 null.
public record DefaultSettings(boolean confirmed, Integer leadTimeDays, Integer reviewCycleDays,
        String reviewCycleSource, Long orderUnitQty, ReviewCycleBasis reviewCycleBasis,
        ReviewCycleSuggestion reviewCycleSuggestion) {

    public record ReviewCycleBasis(LocalDate from, LocalDate to, int productCount) {
    }

    public record ReviewCycleSuggestion(int reviewCycleDays, LocalDate from, LocalDate to, int productCount) {
    }

    public static DefaultSettings from(Optional<Warehouse> warehouse, ProductSettings values, ReviewCycleBasis basis,
            ReviewCycleSuggestion suggestion) {
        boolean confirmed = warehouse.map(Warehouse::hasConfirmedDefaults).orElse(false);
        return new DefaultSettings(confirmed, values.leadTimeDays(), values.reviewCycleDays(),
                confirmed ? "DEFAULT" : "ESTIMATED", values.orderUnitQty(), confirmed ? null : basis, suggestion);
    }
}
