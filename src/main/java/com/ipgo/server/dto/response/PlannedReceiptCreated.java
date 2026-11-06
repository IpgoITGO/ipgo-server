package com.ipgo.server.dto.response;

// R2 응답. reflected는 현재 업무일의 계산 후보 여부이며 runId는 실제 생성/재사용한 계산 번호다.
public record PlannedReceiptCreated(Long receiptId, boolean reflected, Long runId) {
}
