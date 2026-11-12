package com.ipgo.server.dto.response;

// #190 알림 등록 결과. 로그아웃할 때 이 id로 등록을 지운다.
public record PushSubscriptionSaved(long subscriptionId) {
}
