package com.ipgo.server.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "warehouses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "workplace_code", length = 50)
    private String workplaceCode;

    @Column(name = "default_lead_time_days")
    private Integer defaultLeadTimeDays;

    @Column(name = "default_review_cycle_days")
    private Integer defaultReviewCycleDays;

    @Column(name = "default_order_unit_qty")
    private Long defaultOrderUnitQty;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Warehouse(Long userId, String name) {
        this.userId = userId;
        this.name = name;
        this.createdAt = Instant.now();
    }

    // 셀러가 S2로 기본값 세 칸을 저장했는지
    public boolean hasConfirmedDefaults() {
        return defaultLeadTimeDays != null && defaultReviewCycleDays != null && defaultOrderUnitQty != null;
    }

    public void confirmDefaults(int leadTimeDays, int reviewCycleDays, long orderUnitQty) {
        this.defaultLeadTimeDays = leadTimeDays;
        this.defaultReviewCycleDays = reviewCycleDays;
        this.defaultOrderUnitQty = orderUnitQty;
    }
}
