package com.ipgo.server.dto.request;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// 보낸 칸만 바꾼다. 상품명은 공백만 보내면 안 된다.
public record ProductUpdateRequest(
        @Size(min = 1, max = 200) String skuName,
        @PositiveOrZero Long unitCost,
        Boolean isActive) {

    public ProductUpdateRequest {
        skuName = skuName == null ? null : skuName.strip();
    }
}
