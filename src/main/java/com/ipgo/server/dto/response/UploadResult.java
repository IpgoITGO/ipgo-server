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
                List.of(), error(upload), new ReceiptMatching("NOT_EVALUATED", List.of()));
    }

    // U2와 U3(기간별 목록)가 같은 실패 정보를 내보내도록 한곳에서 꺼낸다.
    // 저장할 때 엔진 장애 문구는 빼고 셀러용 안내만 남기므로 그대로 돌려준다.
    public static UploadProcessingReport.Failure error(UploadFile upload) {
        var report = upload.getValidationReport();
        return report == null ? null : report.error();
    }
}
