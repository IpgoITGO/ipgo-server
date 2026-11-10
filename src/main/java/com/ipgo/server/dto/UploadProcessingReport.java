package com.ipgo.server.dto;

import java.time.LocalDate;
import java.util.List;

// upload_files.validation_report에 저장한다. 원본 파일·jobId·엔진 내부 예외 문자열은 담지 않는다.
// 단, 셀러 입력 문제(INVALID_SOURCE)일 때 엔진이 셀러에게 보여 주려고 만든 이유 문장은 reason에 담는다(#164).
public record UploadProcessingReport(LocalDate asOfDate, Validation validation,
        Integer estimatedReviewCycleDays, Failure error) {
    public record Validation(int checkedSkuCount, int targetSkuCount, List<ExcludedSku> excludedSkus) { }
    public record ExcludedSku(long productId, String skuBarcode, String skuName, LocalDate date, String reason) { }
    // message: 서버의 고정 안내. reason: 엔진이 찾은 실제 이유(없으면 null). details.reason: 구분 코드(없으면 null).
    public record Failure(String code, String message, String reason, FailureDetails details) {
        public Failure(String code, String message) { this(code, message, null, null); }
    }
    public record FailureDetails(String reason) { }
}
