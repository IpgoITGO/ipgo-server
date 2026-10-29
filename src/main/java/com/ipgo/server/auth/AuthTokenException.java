package com.ipgo.server.auth;

public class AuthTokenException extends RuntimeException {
    private final String code;

    AuthTokenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
