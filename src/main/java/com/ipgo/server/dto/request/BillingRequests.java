package com.ipgo.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class BillingRequests {
    private BillingRequests() { }
    public enum CancellationReason { PRICE, NOT_USING, MISSING_FEATURE, PREDICTION_QUALITY, OTHER_SERVICE, OTHER }
    public record Cancellation(CancellationReason reason,
            @Size(max=6) java.util.List<@jakarta.validation.constraints.NotNull CancellationReason> reasons,
            @Size(max=500) @Pattern(regexp="[^\\x00]*") String note) {
        @Override public String toString() { return "BillingCancellation[redacted]"; }
    }
    public record Registration(@NotBlank @Pattern(regexp="START_SUBSCRIPTION|REPLACE_METHOD") String purpose,
            @Pattern(regexp="seller|business") String planId, @Size(max=40) String priceVersion,
            @NotBlank @Size(max=40) String consentVersion) { }
    public record Complete(@NotBlank @Size(max=300) String authKey,
            @NotBlank @Size(max=50) String customerKey, @NotBlank @Size(max=100) String state) {
        @Override public String toString() { return "BillingComplete[redacted]"; }
    }
    public record RefundAccount(@NotBlank @Pattern(regexp="[0-9]{2,3}") String bank,
            @NotBlank @Pattern(regexp="[0-9]{1,20}") String accountNumber,
            @NotBlank @Size(max=60) @Pattern(regexp="[^\\p{Cntrl}]+") String holderName) {
        @Override public String toString() { return "RefundAccount[redacted]"; }
    }
    public record CancelPayment(@NotBlank @Size(max=200) String reason,@jakarta.validation.Valid RefundAccount refundReceiveAccount) {
        public CancelPayment(String reason) { this(reason,null); }
        @Override public String toString() { return "CancelPayment[redacted]"; }
    }
    public record Resume(@NotBlank @Size(max=40) String consentVersion) { }
}
