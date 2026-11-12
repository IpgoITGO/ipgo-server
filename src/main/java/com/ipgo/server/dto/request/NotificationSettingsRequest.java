package com.ipgo.server.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

// #190 알림 설정 바꾸기. 보낸 칸만 바뀐다(null이면 그대로).
public record NotificationSettingsRequest(Boolean enabled, @Min(0) @Max(23) Integer sendHour,
        @Size(min = 1, max = 64) String timeZone) {
}
