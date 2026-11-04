package com.ipgo.server.dto.request;

import com.ipgo.server.util.EmailNormalizer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationRequest(@NotBlank @Email @Size(max = 254) String email) {
    public EmailVerificationRequest { email = EmailNormalizer.normalize(email); }
}
