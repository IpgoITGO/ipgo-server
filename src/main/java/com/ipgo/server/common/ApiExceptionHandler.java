package com.ipgo.server.common;

import com.ipgo.server.auth.EmailVerificationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(EmailVerificationException.class)
    ResponseEntity<ApiError> emailVerification(EmailVerificationException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidField(MethodArgumentNotValidException exception) {
        var field = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField()).sorted().findFirst().orElse(null);
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "입력값의 형식과 길이를 확인해주세요.",
                field == null ? null : new ApiError.FieldDetails(field)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "입력값의 형식과 길이를 확인해주세요."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> responseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ApiError(exception.getStatusCode().value() == 409 ? "DUPLICATE_EMAIL" : "INVALID_INPUT",
                        exception.getReason()));
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    ResponseEntity<ApiError> missingUser() {
        return ResponseEntity.status(401).body(new ApiError("UNAUTHENTICATED", "로그인이 필요합니다."));
    }
}
