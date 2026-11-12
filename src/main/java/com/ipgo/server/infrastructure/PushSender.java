package com.ipgo.server.infrastructure;

public interface PushSender {
    // 음수: 응답을 확정할 수 없음. 이 경우 자동 재시도하지 않는다.
    Result send(String endpoint, String p256dh, String auth, byte[] payload);
    record Result(int status, java.time.Duration retryAfter) {
        public Result(int status) { this(status, java.time.Duration.ZERO); }
    }
}
