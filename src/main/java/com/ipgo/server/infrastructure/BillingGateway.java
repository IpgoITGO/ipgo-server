package com.ipgo.server.infrastructure;

public interface BillingGateway {
    record Issued(String billingKey, String customerKey, String maskedNumber) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
    record Charge(String orderId, String customerKey, long amount, String orderName) { }
    record Payment(String paymentKey, String orderId, long totalAmount, String currency, String status, String merchantId, java.time.Instant approvedAt) { }
    final class Failure extends RuntimeException {
        private final boolean rejected;
        private final String code;
        public Failure(boolean rejected,String code) { super("Billing provider request did not complete"); this.rejected=rejected; this.code=code; }
        public boolean rejected() { return rejected; }
        public String code() { return code; }
    }
    Issued issue(String authKey,String customerKey,String idempotencyKey);
    Payment charge(String billingKey,Charge charge,String idempotencyKey);
    Payment lookup(String orderId);
    void delete(String billingKey);
}
