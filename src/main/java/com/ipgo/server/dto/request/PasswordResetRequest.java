package com.ipgo.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// #173 새 비밀번호 저장. 비밀번호 규칙은 회원가입(SignupRequest)과 같다.
public record PasswordResetRequest(@NotBlank @Size(max = 100) String resetToken,
        @NotBlank @Size(min = 8, max = 16)
        @Pattern(regexp = "(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*\\p{Punct})[\\x21-\\x7E]+") String newPassword) {
}
