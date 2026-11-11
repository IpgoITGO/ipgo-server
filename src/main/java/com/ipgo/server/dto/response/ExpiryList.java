package com.ipgo.server.dto.response;

import java.time.LocalDate;
import java.util.List;

// #168 유통기한 목록. 기준일에 남은 재고가 있는 재고관리단위(LOT)만, 유통기한이 빠른 순으로 준다.
// asOfDate는 남은 수량을 센 날(마지막 수불 날짜)이고, 업로드 전이면 null이다.
public record ExpiryList(LocalDate asOfDate, int imminentDays, Summary summary, List<Item> items) {

    public enum Status { EXPIRED, IMMINENT, OK, UNKNOWN }

    // 상태별 LOT 수. status 필터와 상관없이 센다(productId가 있으면 그 상품 안에서만).
    public record Summary(int expired, int imminent, int ok, int unknown) {
    }

    // daysLeft = 유통기한 - 오늘(서울). 유통기한을 모르면 expiryDate·daysLeft가 null이고 status는 UNKNOWN이다.
    public record Item(long productId, String skuBarcode, String skuName, LocalDate receivedOn,
            LocalDate expiryDate, Long daysLeft, long remainingQty, Status status) {
    }
}
