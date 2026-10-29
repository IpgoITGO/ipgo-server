package com.ipgo.server.exception;

import org.springframework.http.HttpStatus;

public class EmailVerificationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public EmailVerificationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
