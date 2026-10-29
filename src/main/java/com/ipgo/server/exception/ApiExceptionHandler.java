package com.ipgo.server.exception;

import com.ipgo.server.dto.response.ApiError;

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
    @ExceptionHandler(AuthTokenException.class)
    ResponseEntity<ApiError> authToken(AuthTokenException exception) {
        return ResponseEntity.status(401).header("Cache-Control", "no-store")
                .body(new ApiError(exception.code(), exception.getMessage()));
    }

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
        String message = "password".equals(field)
                ? "비밀번호는 8~10자로 영문 대문자, 소문자, 숫자, 특수기호를 각각 1개 이상 포함해야 하며, 공백 없이 ASCII 문자만 사용할 수 있습니다."
                : "입력값의 형식과 길이를 확인해주세요.";
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", message,
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
