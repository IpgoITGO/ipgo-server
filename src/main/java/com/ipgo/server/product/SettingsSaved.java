package com.ipgo.server.product;

// S2 · S3 응답. runId는 계산 엔진 연동 전까지 null이다.
public record SettingsSaved(boolean saved, Long runId) {
    static SettingsSaved withoutRun() {
        return new SettingsSaved(true, null);
    }
}
