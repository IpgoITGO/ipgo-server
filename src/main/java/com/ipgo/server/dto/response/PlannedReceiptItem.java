package com.ipgo.server.dto.response;

import com.ipgo.server.entity.PlannedReceipt;
import com.ipgo.server.entity.Product;

import java.time.LocalDate;
import java.util.List;

// R1 목록 한 줄과 R3 수정 응답
public record PlannedReceiptItem(Long receiptId, Long productId, String skuName, Long quantity,
        LocalDate expectedOn, LocalDate receivedOn, String status, boolean reflected) {

    public static PlannedReceiptItem from(PlannedReceipt receipt, Product product) {
        return new PlannedReceiptItem(receipt.getId(), receipt.getProductId(), product.getSkuName(),
                receipt.getQuantity(), receipt.getExpectedOn(), receipt.getReceivedOn(), receipt.getStatus(),
                receipt.isReflected());
    }

    public record Page(List<PlannedReceiptItem> items, int total) {
    }
}
