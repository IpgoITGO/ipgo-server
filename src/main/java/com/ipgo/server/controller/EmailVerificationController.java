package com.ipgo.server.controller;

import com.ipgo.server.dto.request.EmailVerificationConfirmRequest;
import com.ipgo.server.dto.request.EmailVerificationRequest;
import com.ipgo.server.dto.response.EmailAvailabilityResponse;
import com.ipgo.server.dto.response.EmailVerificationConfirmedResponse;
import com.ipgo.server.dto.response.EmailVerificationSentResponse;
import com.ipgo.server.service.EmailVerificationService;

import jakarta.validation.Valid;
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
    public EmailAvailabilityResponse available(@Valid @ModelAttribute EmailVerificationRequest request) {
        return new EmailAvailabilityResponse(service.available(request.email()));
    }

    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EmailVerificationSentResponse send(@Valid @RequestBody EmailVerificationRequest request) {
        service.requestCode(request.email());
        return new EmailVerificationSentResponse(600);
    }

    @PostMapping("/email-verifications/confirm")
    public EmailVerificationConfirmedResponse confirm(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        return new EmailVerificationConfirmedResponse(service.confirm(request.email(), request.code()));
    }

}
