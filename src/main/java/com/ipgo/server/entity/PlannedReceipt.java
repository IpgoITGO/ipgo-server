package com.ipgo.server.entity;

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
// RECEIVED는 정제 입고 매칭으로만 전환한다. 지연 상태는 서울 업무일로 읽을 때 판단한다.
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

    public PlannedReceipt(Long productId, Long warehouseId, long quantity, LocalDate expectedOn) {
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.expectedOn = expectedOn;
        this.status = expectedOn == null ? NO_DATE : PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // 값이 null인 칸은 바꾸지 않는다. 도착일을 새로 넣으면 계산에 반영된다.
    public void update(Long quantity, LocalDate expectedOn) {
        requireMutable();
        if (quantity != null) this.quantity = quantity;
        if (expectedOn != null) {
            this.expectedOn = expectedOn;
            if (NO_DATE.equals(status)) this.status = PENDING;
        }
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        requireMutable();
        this.status = CANCELLED;
        this.updatedAt = Instant.now();
    }

    public boolean isReceived() { return "RECEIVED".equals(status); }

    public boolean isMatchable() {
        return expectedOn != null && (PENDING.equals(status) || "OVERDUE".equals(status));
    }

    public void receive(LocalDate date) {
        if (!isMatchable()) throw new IllegalStateException("Receipt is not matchable");
        this.status = "RECEIVED";
        this.receivedOn = java.util.Objects.requireNonNull(date);
        this.updatedAt = Instant.now();
    }

    // 정정 service가 같은 창고/사건의 활성 배정을 확인하고 잠근 transaction에서만 호출한다.
    public void releaseForCorrection(LocalDate date) {
        if (!isReceived() || !java.util.Objects.equals(receivedOn,date))
            throw new IllegalStateException("Receipt allocation does not match correction");
        this.status = expectedOn == null ? NO_DATE : PENDING;
        this.receivedOn = null;
        this.updatedAt = Instant.now();
    }

    private void requireMutable() {
        if (isReceived()) throw com.ipgo.server.exception.ProductException.receiptReceived();
    }

    public boolean isCancelled() {
        return CANCELLED.equals(status);
    }

    // 날짜 경과만으로 DB를 쓰지 않는다. 입고 완료/취소 상태가 날짜 판정보다 우선한다.
    public String statusOn(LocalDate today) {
        if (isReceived() || isCancelled()) return status;
        if (expectedOn == null) return NO_DATE;
        return expectedOn.isBefore(today) ? "OVERDUE" : PENDING;
    }

    // 계산 후보. 수불부에 이미 포함된 날짜(asOfDate 이하)는 계산 입력에서 추가 제외한다.
    public boolean isReflectedOn(LocalDate today) {
        return PENDING.equals(statusOn(today));
    }
}
