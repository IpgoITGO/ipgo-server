package com.ipgo.server.dto.request;

import com.ipgo.server.dto.response.ReceiptCorrection.Action;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class ReceiptCorrectionRequest {
    private ReceiptCorrectionRequest() { }
    public record Preview(@NotNull @Pattern(regexp="[a-f0-9]{64}") String expectedVersion,
            @NotNull Action action, @NotNull @Size(max=200) List<@NotNull @Positive Long> receiptIds) { }
    public record Decide(@NotNull @Pattern(regexp="[a-f0-9]{64}") String expectedVersion,
            @NotNull Action action, @NotNull @Size(max=200) List<@NotNull @Positive Long> receiptIds,
            @NotBlank @Size(max=500) String reason, @NotNull UUID idempotencyKey) {
        public Preview preview() { return new Preview(expectedVersion,action,receiptIds); }
    }
}
