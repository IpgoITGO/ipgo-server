package com.ipgo.server.entity;

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

    @Column(name = "activation_mode", nullable = false, length = 20)
    private String activationMode = "AUTO";

    @Column(name = "auto_inactive_reason", length = 30)
    private String autoInactiveReason;

    @Column(name = "explanation_mode", length = 20)
    private String explanationMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Product(Long userId, String skuBarcode, String skuName) {
        this.userId = userId;
        this.skuBarcode = skuBarcode;
        this.skuName = skuName;
        this.active = true;
        this.createdAt = Instant.now();
    }

    // 값이 null인 칸은 바꾸지 않는다.
    public void update(String skuName, Long unitCost, Boolean active) {
        if (skuName != null) this.skuName = skuName;
        if (unitCost != null) this.unitCost = BigDecimal.valueOf(unitCost);
        if (active != null) {
            this.active = active;
            this.activationMode = active ? "MANUAL_ON" : "MANUAL_OFF";
            this.autoInactiveReason = null;
        }
    }
    public void selectAutomatic() {
        if (!"AUTO".equals(activationMode)) autoInactiveReason = null;
        activationMode = "AUTO";
    }

    public void applyAutomaticActivity(boolean active) {
        if (!"AUTO".equals(activationMode)) return;
        this.active = active;
        this.autoInactiveReason = active ? null : "NO_RECENT_RECORDS";
    }
}
