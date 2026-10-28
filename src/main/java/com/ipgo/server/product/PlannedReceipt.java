package com.ipgo.server.product;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 기존 발주 물량. 도착 예정일이 있으면 PENDING, 없으면 NO_DATE로 시작한다.
// RECEIVED(수불부 입고 확인)와 OVERDUE(기준일이 지났는데 입고 없음)는 정제 연동 뒤 판정한다.
@Entity
@Table(name = "planned_receipts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlannedReceipt {
    static final String PENDING = "PENDING";
    static final String NO_DATE = "NO_DATE";
    static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "expected_on")
    private LocalDate expectedOn;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "received_on")
    private LocalDate receivedOn;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    PlannedReceipt(Long productId, Long warehouseId, long quantity, LocalDate expectedOn) {
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.expectedOn = expectedOn;
        this.status = expectedOn == null ? NO_DATE : PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // 값이 null인 칸은 바꾸지 않는다. 도착일을 새로 넣으면 계산에 반영된다.
    void update(Long quantity, LocalDate expectedOn) {
        if (quantity != null) this.quantity = quantity;
        if (expectedOn != null) {
            this.expectedOn = expectedOn;
            if (NO_DATE.equals(status)) this.status = PENDING;
        }
        this.updatedAt = Instant.now();
    }

    void cancel() {
        this.status = CANCELLED;
        this.updatedAt = Instant.now();
    }

    boolean isCancelled() {
        return CANCELLED.equals(status);
    }

    // 계산에 넣는 물량인지. 지금은 도착 예정일이 있는 PENDING만 반영한다.
    boolean isReflected() {
        return PENDING.equals(status);
    }
}
