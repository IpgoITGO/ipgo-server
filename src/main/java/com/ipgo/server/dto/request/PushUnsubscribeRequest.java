package com.ipgo.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// #190 로그아웃할 때 이 브라우저의 알림 등록 해제. 브라우저가 pushManager.getSubscription()으로 언제든 알 수 있는 주소로 지운다.
public record PushUnsubscribeRequest(@NotBlank @Size(max = 2048) String endpoint) {
}
