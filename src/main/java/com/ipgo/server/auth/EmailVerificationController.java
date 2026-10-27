package com.ipgo.server.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class EmailVerificationController {
    private final EmailVerificationService service;

    public EmailVerificationController(EmailVerificationService service) { this.service = service; }

    @GetMapping("/email-availability")
    public Availability available(@Valid @ModelAttribute EmailRequest request) {
        return new Availability(service.available(request.email()));
    }

    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Sent send(@Valid @RequestBody EmailRequest request) {
        service.requestCode(request.email());
        return new Sent(600);
    }

    @PostMapping("/email-verifications/confirm")
    public Verified confirm(@Valid @RequestBody ConfirmRequest request) {
        return new Verified(service.confirm(request.email(), request.code()));
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
        public EmailRequest { email = AuthService.normalizeEmail(email); }
    }
    public record ConfirmRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
        public ConfirmRequest { email = AuthService.normalizeEmail(email); }
    }
    public record Availability(boolean available) { }
    public record Sent(int expiresIn) { }
    public record Verified(String verificationToken) { }
}
