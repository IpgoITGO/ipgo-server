package com.ipgo.server.dto.response;

import java.util.List;

// #111 보관 비용 설정. ratePerCbm은 원 / CBM · 월, 상자 크기는 cm. 입력하지 않았으면 null · 빈 목록이다.
public record StorageCost(Double ratePerCbm, List<Box> boxes) {

    public record Box(Long productId, Double width, Double depth, Double height) {
    }
}
