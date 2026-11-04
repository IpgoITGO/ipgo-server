package com.ipgo.server.dto.response;

import com.ipgo.server.entity.Warehouse;

import java.util.Optional;

// S1 응답. confirmed가 false면 제안값과 ESTIMATED를, true면 저장한 기본값과 DEFAULT를 돌려준다.
public record DefaultSettings(boolean confirmed, Integer leadTimeDays, Integer reviewCycleDays,
        String reviewCycleSource, Long orderUnitQty) {

    public static DefaultSettings from(Optional<Warehouse> warehouse) {
        boolean confirmed = warehouse.map(Warehouse::hasConfirmedDefaults).orElse(false);
        ProductSettings values = ProductSettings.defaults(warehouse);
        return new DefaultSettings(confirmed, values.leadTimeDays(), values.reviewCycleDays(),
                confirmed ? "DEFAULT" : "ESTIMATED", values.orderUnitQty());
    }
}
