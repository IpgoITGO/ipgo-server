package com.ipgo.server.exception;

import java.util.Map;

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

    // 파일 자체를 받을 수 없을 때(형식 · 손상 · 빈 파일). 화면은 이 코드로 "거절된 파일" 팝업을 띄운다.
    public static UploadException invalidFormat(String fileName, String reason) {
        return new UploadException(HttpStatus.BAD_REQUEST, "UPLOAD_INVALID_FORMAT", reason,
                Map.of("fileName", fileName, "reason", reason));
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Object details() { return details; }
}
