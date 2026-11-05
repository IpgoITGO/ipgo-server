package com.ipgo.server.dto.response;

import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.entity.UploadFile;
import java.time.LocalDate;
import java.util.List;

public record UploadResult(long uploadId, String status, LocalDate periodStart, LocalDate periodEnd,
        LocalDate asOfDate, Long runId, UploadProcessingReport.Validation validation,
        List<MatchedReceipt> matchedReceipts, UploadProcessingReport.Failure error, ReceiptMatching receiptMatching) {
    public record ReceiptMatching(String status, List<ReceiptMatchingIssue> issues) { }
    public record ReceiptMatchingIssue(long productId, LocalDate stockDate, String reason) { }
    public record MatchedReceipt(long receiptId, String skuName, long quantity, LocalDate receivedOn, String status) { }

    public static UploadResult from(UploadFile upload) {
        var report = upload.getValidationReport();
        // 계산 번호와 입고 판정 snapshot은 소유자 확인 후 조회 서비스에서 연결한다.
        return new UploadResult(upload.getId(), upload.getStatus(), upload.getPeriodStart(), upload.getPeriodEnd(),
                report == null ? null : report.asOfDate(), null, report == null ? null : report.validation(),
                List.of(), report == null ? null : report.error(), new ReceiptMatching("NOT_EVALUATED", List.of()));
    }
}
