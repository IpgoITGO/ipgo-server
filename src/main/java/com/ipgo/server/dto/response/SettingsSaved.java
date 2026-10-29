package com.ipgo.server.dto.response;

// S2 · S3 응답. runId는 계산 엔진 연동 전까지 null이다.
public record SettingsSaved(boolean saved, Long runId) {
    public static SettingsSaved withoutRun() {
        return new SettingsSaved(true, null);
    }
}
