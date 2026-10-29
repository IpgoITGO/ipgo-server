package com.ipgo.server.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 계산 실행 안의 상품 1개 결과. 엔진이 소수로 계산한 재고 · 수요는 NUMERIC 그대로 읽는다.
@Entity
@Table(name = "calculation_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalculationResult {
    public static final String OK = "OK";
    public static final String NOT_NEEDED = "NOT_NEEDED";
    public static final String NOT_CALCULABLE = "NOT_CALCULABLE";
    public static final String EXCLUDED = "EXCLUDED";
    public static final String REUPLOAD_REQUIRED = "REUPLOAD_REQUIRED";

    @Id
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "origin_date", nullable = false)
    private LocalDate originDate;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "method", length = 10)
    private String method;

    @Column(name = "method_reason", length = 30)
    private String methodReason;

    @Column(name = "multiplier", nullable = false, precision = 5, scale = 3)
    private BigDecimal multiplier;

    @Column(name = "horizon_days", nullable = false)
    private Integer horizonDays;

    @Column(name = "start_stock_qty", precision = 18, scale = 3)
    private BigDecimal startStockQty;

    @Column(name = "planned_receipt_qty")
    private Long plannedReceiptQty;

    @Column(name = "forecast_outbound_qty", precision = 18, scale = 3)
    private BigDecimal forecastOutboundQty;

    @Column(name = "pre_arrival_unmet_qty", precision = 18, scale = 3)
    private BigDecimal preArrivalUnmetQty;

    @Column(name = "recommended_qty")
    private Long recommendedQty;

    @Column(name = "recommended_amount", precision = 18, scale = 2)
    private BigDecimal recommendedAmount;

    @Column(name = "expected_shortage_on")
    private LocalDate expectedShortageOn;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "reason")
    private String reason;

    @Column(name = "pre_arrival_risk", precision = 5, scale = 4)
    private BigDecimal preArrivalRisk;

    @Column(name = "accuracy_grade", length = 10)
    private String accuracyGrade;

    @Column(name = "valid_observation_days")
    private Integer validObservationDays;

    @Column(name = "cover_success_count")
    private Integer coverSuccessCount;

    @Column(name = "cover_trial_count")
    private Integer coverTrialCount;

    @Column(name = "forecast_error_rate", precision = 5, scale = 4)
    private BigDecimal forecastErrorRate;

    @Column(name = "forecast_range_low", precision = 18, scale = 3)
    private BigDecimal forecastRangeLow;

    @Column(name = "forecast_range_high", precision = 18, scale = 3)
    private BigDecimal forecastRangeHigh;
}
