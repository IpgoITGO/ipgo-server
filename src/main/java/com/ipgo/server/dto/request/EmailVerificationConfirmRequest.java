package com.ipgo.server.dto.request;

import com.ipgo.server.util.EmailNormalizer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record EmailVerificationConfirmRequest(@NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
    public EmailVerificationConfirmRequest { email = EmailNormalizer.normalize(email); }
}
