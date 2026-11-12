package com.ipgo.server.exception;

import org.springframework.http.HttpStatus;

// 내 계정(탈퇴 등) 요청의 오류. 입력 칸 오류면 details.field로 그 칸을 알려 준다.
public class AccountException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String field;

    private AccountException(HttpStatus status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String field() { return field; }

    public static AccountException wrongPassword() {
        return wrongPassword("password");
    }

    // field: 틀린 비밀번호를 넣은 칸(탈퇴는 password, 비밀번호 바꾸기는 currentPassword)
    public static AccountException wrongPassword(String field) {
        return new AccountException(HttpStatus.BAD_REQUEST, "WRONG_PASSWORD", "비밀번호가 맞지 않아요.", field);
    }
}
