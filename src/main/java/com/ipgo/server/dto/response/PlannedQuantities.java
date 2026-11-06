package com.ipgo.server.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

// #119 내 입고량 목록. 상품 ID 순서이고, 정하지 않은 상품은 목록에 없다.
public record PlannedQuantities(List<Item> items) {

    public record Item(Long productId, Long quantity, String source, OffsetDateTime decidedAt, Long calculationRunId) {
    }
}
