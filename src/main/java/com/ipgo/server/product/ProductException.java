package com.ipgo.server.product;

import org.springframework.http.HttpStatus;

public class ProductException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    ProductException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static ProductException notFound() {
        return new ProductException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
    }

    static ProductException forbidden() {
        return new ProductException(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한을 확인해주세요.");
    }

    static ProductException invalidInput() {
        return new ProductException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력값의 형식과 길이를 확인해주세요.");
    }
}
