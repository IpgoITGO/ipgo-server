package com.ipgo.server.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// #173 비밀번호 재설정 코드 요청
public record PasswordResetCodeRequest(@NotBlank @Email @Size(max = 254) String email) {
}
