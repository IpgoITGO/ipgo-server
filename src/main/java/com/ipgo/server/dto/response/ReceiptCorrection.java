package com.ipgo.server.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

public final class ReceiptCorrection {
    private ReceiptCorrection() { }
    @Schema(name = "ReceiptCorrectionStateResponse")
    public enum State { OPEN, RESOLVED, ALL }
    @Schema(name = "ReceiptCorrectionActionResponse")
    public enum Action { KEEP, REPLACE }
    @Schema(name = "ReceiptCorrectionSourceResponse")
    public record Source(long uploadId, int revision, boolean present, long quantity, boolean valid, String fingerprint) { }
    @Schema(name = "ReceiptCorrectionAllocationResponse")
    public record Allocation(long receiptId, long quantity, String skuName, LocalDate receivedOn) { }
    @Schema(name = "ReceiptCorrectionCandidateResponse")
    public record Candidate(long receiptId, long quantity, LocalDate expectedOn, String status, boolean selectable) { }
    @Schema(name = "ReceiptCorrectionCapabilitiesResponse")
    public record Capabilities(boolean keep, boolean replace, boolean clear) { }
    @Schema(name = "ReceiptCorrectionDecisionResponse")
    public record Decision(String decisionId, String action, String reason, long actorUserId, String createdAt) { }
    @Schema(name = "ReceiptCorrectionDetailResponse")
    public record Detail(long reviewId, long warehouseId, long productId, String skuName, LocalDate stockDate,
            String currentState, String version, boolean sourceReady, Source originalSource, Source currentSource,
            List<Allocation> allocations, List<Candidate> candidates, Capabilities capabilities,
            List<String> warnings, Decision lastDecision, List<Decision> decisions) { }
    @Schema(name = "ReceiptCorrectionPageResponse")
    public record Page(List<Detail> items, Long nextCursor) { }
    @Schema(name = "ReceiptCorrectionChangeResponse")
    public record Change(long receiptId, String beforeStatus, String afterStatus, boolean beforeReflected, boolean afterReflected) { }
    @Schema(name = "ReceiptCorrectionPreviewResponse")
    public record Preview(String version, String action, List<Allocation> before, List<Allocation> after,
            List<Change> changes, List<String> warnings, boolean mayRecalculate) { }
    @Schema(name = "ReceiptCorrectionSavedResponse")
    public record Saved(String decisionId, String currentState, String version, Long runId) { }
}
