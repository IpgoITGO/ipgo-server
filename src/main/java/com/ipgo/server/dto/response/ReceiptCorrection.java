package com.ipgo.server.dto.response;

import java.time.LocalDate;
import java.util.List;

public final class ReceiptCorrection {
    private ReceiptCorrection() { }
    public enum State { OPEN, RESOLVED, ALL }
    public enum Action { KEEP, REPLACE }
    public record Source(long uploadId, int revision, boolean present, long quantity, boolean valid, String fingerprint) { }
    public record Allocation(long receiptId, long quantity, String skuName, LocalDate receivedOn) { }
    public record Candidate(long receiptId, long quantity, LocalDate expectedOn, String status, boolean selectable) { }
    public record Capabilities(boolean keep, boolean replace, boolean clear) { }
    public record Decision(String decisionId, String action, String reason, long actorUserId, String createdAt) { }
    public record Detail(long reviewId, long warehouseId, long productId, String skuName, LocalDate stockDate,
            String currentState, String version, boolean sourceReady, Source originalSource, Source currentSource,
            List<Allocation> allocations, List<Candidate> candidates, Capabilities capabilities,
            List<String> warnings, Decision lastDecision, List<Decision> decisions) { }
    public record Page(List<Detail> items, Long nextCursor) { }
    public record Change(long receiptId, String beforeStatus, String afterStatus, boolean beforeReflected, boolean afterReflected) { }
    public record Preview(String version, String action, List<Allocation> before, List<Allocation> after,
            List<Change> changes, List<String> warnings, boolean mayRecalculate) { }
    public record Saved(String decisionId, String currentState, String version, Long runId) { }
}
