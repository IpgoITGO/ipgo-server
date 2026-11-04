package com.ipgo.server.exception;

import org.springframework.http.HttpStatus;

public class UploadException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Object details;

    public UploadException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public UploadException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Object details() { return details; }
}
