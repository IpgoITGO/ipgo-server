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

// 재고관리단위(LOT): 같은 창고의 sku_mixed_id + 입고일이 같으면 같은 단위다.
@Entity
@Table(name = "inventory_units")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "source_inventory_key", nullable = false, updatable = false, length = 100)
    private String sourceInventoryKey;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    InventoryUnit(Long productId, Long warehouseId, String sourceInventoryKey, LocalDate receivedOn) {
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.sourceInventoryKey = sourceInventoryKey;
        this.receivedOn = receivedOn;
        this.createdAt = Instant.now();
    }
}
