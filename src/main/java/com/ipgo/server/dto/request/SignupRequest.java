package com.ipgo.server.dto.request;

import com.ipgo.server.util.EmailNormalizer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 16)
        @Pattern(regexp = "(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*\\p{Punct})[\\x21-\\x7E]+") String password,
        @NotBlank @Size(min = 1, max = 50) String companyName,
        @NotBlank @Size(min = 1, max = 50) String managerName,
        @Pattern(regexp = "[0-9]{10}") String businessRegNo,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String verificationToken) {

    public SignupRequest {
        email = EmailNormalizer.normalize(email);
        companyName = companyName == null ? null : companyName.strip();
        managerName = managerName == null ? null : managerName.strip();
        businessRegNo = businessRegNo == null || businessRegNo.isBlank() ? null : businessRegNo;
    }
}
