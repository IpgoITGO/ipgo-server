package com.ipgo.server.product;

import java.math.BigDecimal;
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
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "sku_barcode", nullable = false, updatable = false, length = 100)
    private String skuBarcode;

    @Column(name = "sku_name", nullable = false, length = 200)
    private String skuName;

    @Column(name = "unit_cost", precision = 18, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "explanation_mode", length = 20)
    private String explanationMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    Product(Long userId, String skuBarcode, String skuName) {
        this.userId = userId;
        this.skuBarcode = skuBarcode;
        this.skuName = skuName;
        this.active = true;
        this.createdAt = Instant.now();
    }

    // 값이 null인 칸은 바꾸지 않는다.
    void update(String skuName, Long unitCost, Boolean active) {
        if (skuName != null) this.skuName = skuName;
        if (unitCost != null) this.unitCost = BigDecimal.valueOf(unitCost);
        if (active != null) this.active = active;
    }
}
