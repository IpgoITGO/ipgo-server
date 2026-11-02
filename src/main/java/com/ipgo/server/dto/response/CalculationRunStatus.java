package com.ipgo.server.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

// C2 계산 상태. status: QUEUED · RUNNING · DONE · FAILED.
// errorMessage는 FAILED일 때, summary는 DONE일 때만 있다. policy는 저장 위치가 #27에서 정해질 때까지 null이다.
public record CalculationRunStatus(Long runId, String status, LocalDate asOfDate, String engineVersion, Policy policy,
        String errorMessage, OffsetDateTime createdAt, OffsetDateTime completedAt, ReplenishmentNeeds.Summary summary) {

    public record Policy(String defaultMethod, BigDecimal multiplier) {
    }
}
