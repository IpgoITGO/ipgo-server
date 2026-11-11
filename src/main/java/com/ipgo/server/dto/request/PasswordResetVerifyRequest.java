package com.ipgo.server.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// #173 비밀번호 재설정 코드 확인. 코드 모양이 틀려도 INVALID_CODE로 답하려고 형식 검사는 하지 않는다.
public record PasswordResetVerifyRequest(@NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 20) String code) {
}
