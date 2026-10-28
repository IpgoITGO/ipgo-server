package com.ipgo.server.product;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 상품별 입고 조건. null인 칸은 창고 기본값을 따른다.
@Entity
@Table(name = "replenishment_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplenishmentSetting {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "review_cycle_days")
    private Integer reviewCycleDays;

    @Column(name = "order_unit_qty")
    private Long orderUnitQty;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    ReplenishmentSetting(Long productId) {
        this.productId = productId;
    }

    // PUT이므로 보낸 값으로 모두 바꾼다. null을 보내면 기본값을 따르게 된다.
    void replace(Integer leadTimeDays, Integer reviewCycleDays, Long orderUnitQty) {
        this.leadTimeDays = leadTimeDays;
        this.reviewCycleDays = reviewCycleDays;
        this.orderUnitQty = orderUnitQty;
        this.updatedAt = Instant.now();
    }
}
