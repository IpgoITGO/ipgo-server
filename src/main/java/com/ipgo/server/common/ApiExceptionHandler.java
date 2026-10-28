package com.ipgo.server.common;

import com.ipgo.server.upload.UploadException;
import com.ipgo.server.auth.EmailVerificationException;
import com.ipgo.server.product.ProductException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(UploadException.class)
    ResponseEntity<ApiError> uploadError(UploadException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), exception.details()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(new ApiError("FILE_TOO_LARGE", "파일 크기 제한을 초과했습니다."));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<ApiError> invalidUpload(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "multipart의 file 필드에 파일을 첨부해주세요."));
    }

    @ExceptionHandler(EmailVerificationException.class)
    ResponseEntity<ApiError> emailVerification(EmailVerificationException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(ProductException.class)
    ResponseEntity<ApiError> product(ProductException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidField(MethodArgumentNotValidException exception) {
        var field = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField()).sorted().findFirst().orElse(null);
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "입력값의 형식과 길이를 확인해주세요.",
                field == null ? null : new ApiError.FieldDetails(field)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
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
