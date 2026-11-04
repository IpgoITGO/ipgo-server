package com.ipgo.server.dto.response;

import com.ipgo.server.entity.PlannedReceipt;

// R2 응답. reflected가 false면 화면이 "계산에 반영되지 않음"을 안내한다. runId는 계산 엔진 연동 전까지 null이다.
public record PlannedReceiptCreated(Long receiptId, boolean reflected, Long runId) {
    public static PlannedReceiptCreated from(PlannedReceipt receipt) {
        return new PlannedReceiptCreated(receipt.getId(), receipt.isReflected(), null);
    }
}
