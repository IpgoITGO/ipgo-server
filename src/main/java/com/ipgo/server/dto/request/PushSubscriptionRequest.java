package com.ipgo.server.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// #190 브라우저 알림 등록. 브라우저의 PushSubscription.toJSON() 모양 그대로 받는다(expirationTime 등 다른 칸은 무시).
public record PushSubscriptionRequest(@NotBlank @Size(max = 2048) String endpoint, @NotNull @Valid Keys keys) {
    public record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
    }
}
