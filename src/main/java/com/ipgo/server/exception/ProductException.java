package com.ipgo.server.exception;

import org.springframework.http.HttpStatus;

public class ProductException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    ProductException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ProductException notFound() {
        return new ProductException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
    }

    public static ProductException forbidden() {
        return new ProductException(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한을 확인해주세요.");
    }

    public static ProductException invalidInput() {
        return new ProductException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력값의 형식과 길이를 확인해주세요.");
    }
    public static ProductException scenarioConflict() {
        return new ProductException(HttpStatus.CONFLICT, "SCENARIO_STALE", "계산 결과나 자료가 바뀌었어요. 최신 결과를 확인해 주세요.");
    }

    public static ProductException scenarioChanged() {
        return new ProductException(HttpStatus.CONFLICT, "SCENARIO_CHANGED", "다른 요청이 비교 수량을 바꿨어요. 확인 후 다시 입력해 주세요.");
    }

    public static ProductException engineUnavailable() {
        return new ProductException(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_UNAVAILABLE", "시나리오 계산 연결을 준비 중이에요. 잠시 후 다시 시도해 주세요.");
    }

    public static ProductException engineFailure() {
        return new ProductException(HttpStatus.BAD_GATEWAY, "ENGINE_ERROR", "시나리오 계산을 마치지 못했어요. 기존 결과는 그대로예요.");
    }

    public static ProductException engineTimeout() {
        return new ProductException(HttpStatus.GATEWAY_TIMEOUT, "ENGINE_TIMEOUT", "시나리오 계산이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.");
    }

    public static ProductException scenarioStorageFailure() {
        return new ProductException(HttpStatus.SERVICE_UNAVAILABLE, "SCENARIO_SAVE_FAILED", "비교 결과를 저장하지 못했어요. 기존 결과를 확인해 주세요.");
    }
}
