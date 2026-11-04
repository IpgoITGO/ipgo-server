package com.ipgo.server.dto.response;

import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.entity.UploadFile;
import java.time.LocalDate;
import java.util.List;

public record UploadResult(long uploadId, String status, LocalDate periodStart, LocalDate periodEnd,
        LocalDate asOfDate, Long runId, UploadProcessingReport.Validation validation,
        List<MatchedReceipt> matchedReceipts, UploadProcessingReport.Failure error) {
    public record MatchedReceipt(long receiptId, String skuName, long quantity, LocalDate receivedOn, String status) { }

    public static UploadResult from(UploadFile upload) {
        var report = upload.getValidationReport();
        // shortcut: 계산 실행(#27)과 발주 매칭 정책이 확정되면 실제 runId·matchedReceipts를 연결한다.
        return new UploadResult(upload.getId(), upload.getStatus(), upload.getPeriodStart(), upload.getPeriodEnd(),
                report == null ? null : report.asOfDate(), null, report == null ? null : report.validation(),
                List.of(), report == null ? null : report.error());
    }
}
