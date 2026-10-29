package com.ipgo.server.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

// details는 입력 오류면 FieldDetails, 업로드 오류면 파일명·기존 업로드 같은 추가 정보다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Object details) {
    public ApiError(String code, String message) { this(code, message, null); }
    public record FieldDetails(String field) { }
}
