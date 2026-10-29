package com.ipgo.server.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 계산 실행 1번. 대시보드는 읽기만 하고, 행은 계산 연동(#27)에서 만든다.
@Entity
@Table(name = "calculation_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalculationRun {
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
