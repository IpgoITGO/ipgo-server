package com.ipgo.server.product;

import java.time.LocalDate;

// 정제 결과 한 줄: 어떤 상품(바코드)의 어떤 재고관리단위(sku_mixed_id + 입고일)인지.
public record CleanedStockUnit(String skuBarcode, String skuName, String sourceInventoryKey, LocalDate receivedOn) {

    public CleanedStockUnit {
        skuBarcode = required(skuBarcode, 100);
        skuName = required(skuName, 200);
        sourceInventoryKey = required(sourceInventoryKey, 100);
        if (receivedOn == null) throw new IllegalArgumentException("receivedOn is required");
    }

    private static String required(String value, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("required value is blank");
        String stripped = value.strip();
        if (stripped.length() > maxLength) throw new IllegalArgumentException("value is too long");
        return stripped;
    }
}
