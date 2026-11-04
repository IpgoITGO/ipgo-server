package com.ipgo.server.dto;

import java.time.LocalDate;
import java.util.List;

// upload_files.validation_report에 저장한다. 원본 파일·jobId·엔진 예외 문자열은 담지 않는다.
public record UploadProcessingReport(LocalDate asOfDate, Validation validation,
        Integer estimatedReviewCycleDays, Failure error) {
    public record Validation(int checkedSkuCount, int targetSkuCount, List<ExcludedSku> excludedSkus) { }
    public record ExcludedSku(long productId, String skuBarcode, String skuName, LocalDate date, String reason) { }
    public record Failure(String code, String message) { }
}
