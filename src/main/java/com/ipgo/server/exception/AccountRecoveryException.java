package com.ipgo.server.exception;

import org.springframework.http.HttpStatus;

// #173 아이디 찾기 · 비밀번호 재설정 오류. 입력 칸 오류면 details.field로 그 칸을 알려 준다.
public class AccountRecoveryException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String field;

    private AccountRecoveryException(HttpStatus status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String field() { return field; }

    public static AccountRecoveryException accountNotFound() {
        return new AccountRecoveryException(HttpStatus.NOT_FOUND, "NOT_FOUND", "가입한 계정이 없어요.", null);
    }

    public static AccountRecoveryException invalidBusinessNumber() {
        return new AccountRecoveryException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "사업자등록번호 10자리를 확인해 주세요.",
                "businessNumber");
    }

    public static AccountRecoveryException invalidCode() {
        return new AccountRecoveryException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "코드가 맞지 않아요.", "code");
    }

    public static AccountRecoveryException invalidToken() {
        return new AccountRecoveryException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "다시 시도해 주세요.", null);
    }
}
