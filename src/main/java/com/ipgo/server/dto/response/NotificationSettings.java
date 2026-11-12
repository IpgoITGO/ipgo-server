package com.ipgo.server.dto.response;

// #190 입고 필요 알림(Web Push) 설정. sendHour는 timeZone 기준 정시(0~23시)다.
public record NotificationSettings(boolean enabled, int sendHour, String timeZone) {
}
