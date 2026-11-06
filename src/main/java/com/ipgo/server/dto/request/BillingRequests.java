package com.ipgo.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class BillingRequests {
    private BillingRequests() { }
    public record Registration(@NotBlank @Pattern(regexp="START_SUBSCRIPTION|REPLACE_METHOD") String purpose,
            @Pattern(regexp="seller|business") String planId, @Size(max=40) String priceVersion,
            @NotBlank @Size(max=40) String consentVersion) { }
    public record Complete(@NotBlank @Size(max=300) String authKey,
            @NotBlank @Size(max=50) String customerKey, @NotBlank @Size(max=100) String state) {
        @Override public String toString() { return "BillingComplete[redacted]"; }
    }
    public record Resume(@NotBlank @Size(max=40) String consentVersion) { }
}
