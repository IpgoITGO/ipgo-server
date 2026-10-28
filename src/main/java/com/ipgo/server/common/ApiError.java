package com.ipgo.server.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, FieldDetails details) {
    public ApiError(String code, String message) { this(code, message, null); }
    public record FieldDetails(String field) { }
}
