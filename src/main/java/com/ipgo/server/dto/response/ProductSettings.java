package com.ipgo.server.dto.response;

import com.ipgo.server.entity.ReplenishmentSetting;
import com.ipgo.server.entity.Warehouse;

import java.util.Optional;

// 입고 조건 세 칸. 창고 기본값과 상품별로 실제 적용되는 값에 함께 쓴다.
public record ProductSettings(Integer leadTimeDays, Integer reviewCycleDays, Long orderUnitQty) {
    // 셀러가 기본값을 확인하기 전 제안값. 입고 주기는 수불부로 추정할 자료가 아직 없어서 비워 둔다.
    static final ProductSettings SUGGESTED = new ProductSettings(5, null, 1L);

    public static ProductSettings defaults(Optional<Warehouse> warehouse) {
        return warehouse.filter(Warehouse::hasConfirmedDefaults)
                .map(w -> new ProductSettings(w.getDefaultLeadTimeDays(), w.getDefaultReviewCycleDays(),
                        w.getDefaultOrderUnitQty()))
                .orElse(SUGGESTED);
    }

    // 상품별 값이 있으면 그 값을, 없으면 기본값을 쓴다.
    public ProductSettings applying(ReplenishmentSetting override) {
        if (override == null) return this;
        return new ProductSettings(
                override.getLeadTimeDays() != null ? override.getLeadTimeDays() : leadTimeDays,
                override.getReviewCycleDays() != null ? override.getReviewCycleDays() : reviewCycleDays,
                override.getOrderUnitQty() != null ? override.getOrderUnitQty() : orderUnitQty);
    }
}
