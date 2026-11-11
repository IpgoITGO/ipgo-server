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
import tools.jackson.core.JacksonException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BillingException.class)
    ResponseEntity<ApiError> billing(BillingException exception) {
        return ResponseEntity.status(exception.status()).header("Cache-Control", "no-store")
                .body(new ApiError(exception.code(), exception.getMessage()));
    }

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

    @ExceptionHandler(AccountException.class)
    ResponseEntity<ApiError> account(AccountException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage(),
                exception.field() == null ? null : new ApiError.FieldDetails(exception.field())));
    }

    @ExceptionHandler(ProductException.class)
    ResponseEntity<ApiError> product(ProductException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage(),
                exception.field() == null ? null : new ApiError.FieldDetails(exception.field())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidField(MethodArgumentNotValidException exception) {
        var field = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField()).sorted().findFirst().orElse(null);
        String message = "password".equals(field) || "newPassword".equals(field)
                ? "비밀번호는 8~16자로 영문 대문자, 소문자, 숫자, 특수기호를 각각 1개 이상 포함해야 하며, 공백 없이 ASCII 문자만 사용할 수 있습니다."
                : "입력값의 형식과 길이를 확인해주세요.";
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", message,
                field == null ? null : new ApiError.FieldDetails(field)));
    }

    // 숫자 칸에 소수 · 글자를 보내거나 쿼리 값 형식이 틀리면 그 칸 이름을 알려준다. JSON 자체가 깨지면 칸을 알 수 없다.
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        String field = exception instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : jsonField(exception);
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "입력값의 형식과 길이를 확인해주세요.",
                field == null ? null : new ApiError.FieldDetails(field)));
    }

    private static String jsonField(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson) {
                return jackson.getPath().stream().map(JacksonException.Reference::getPropertyName)
                        .filter(name -> name != null).reduce((first, last) -> last).orElse(null);
            }
        }
        return null;
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

    // #173 아이디 찾기 · 비밀번호 재설정
    @ExceptionHandler(AccountRecoveryException.class)
    ResponseEntity<ApiError> accountRecovery(AccountRecoveryException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage(),
                exception.field() == null ? null : new ApiError.FieldDetails(exception.field())));
    }
}
