package com.ipgo.server.exception;

import org.springframework.http.HttpStatus;

// #190 알림 등록 · 설정 요청의 오류. 입력 칸 오류면 details.field로 그 칸을 알려 준다.
public class PushException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String field;

    private PushException(HttpStatus status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String field() { return field; }

    public static PushException unavailable() {
        return new PushException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_UNAVAILABLE", "지금은 알림을 등록할 수 없어요.", null);
    }

    public static PushException accountWithdrawn() {
        return new PushException(HttpStatus.CONFLICT, "ACCOUNT_WITHDRAWN", "탈퇴한 계정이에요.", null);
    }

    public static PushException invalid(String field) {
        return new PushException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력값의 형식과 길이를 확인해주세요.", field);
    }

    public static PushException notFound() {
        return new PushException(HttpStatus.NOT_FOUND, "NOT_FOUND", "알림 등록을 찾을 수 없어요.", null);
    }
}
