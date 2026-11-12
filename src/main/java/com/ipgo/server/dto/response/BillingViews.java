package com.ipgo.server.dto.response;

import java.time.Instant;
import java.util.List;

public final class BillingViews {
    private BillingViews() { }
    public record Plan(String planId, String priceVersion, long amount, String currency, String interval,
            int products, Integer monthlyInsights, boolean testMode, int trialDays, String consentVersion) { }
    public record Entitlements(boolean canCalculate, boolean canUpload, boolean canGenerateInsight,
            int productLimit, Integer monthlyInsightLimit, String reason) { }
    public record Subscription(String planId, String status, Instant trialEnd, Instant periodStart,
            Instant periodEnd, Instant nextChargeAt, boolean cancelAtPeriodEnd, Instant graceUntil,
            long amount, String priceVersion, Entitlements entitlements) { }
    public record Registration(String sessionId, String customerKey, String state, Instant expiresAt,
            String successUrl, String failUrl) { }
    public record Session(String sessionId, String status, String invoiceId, String paymentStatus, String errorCode) { }
    public record Method(String id, String maskedNumber, boolean current, String status) { }
    public record Invoice(String invoiceId, String planId, long amount, String currency, Instant periodStart,
            Instant periodEnd, String status, String paymentStatus, Instant paidAt, String refundStatus, Instant canceledAt, String paymentMethod) {
        public Invoice(String invoiceId,String planId,long amount,String currency,Instant periodStart,Instant periodEnd,String status,String paymentStatus,Instant paidAt,String refundStatus,Instant canceledAt) {
            this(invoiceId,planId,amount,currency,periodStart,periodEnd,status,paymentStatus,paidAt,refundStatus,canceledAt,null);
        }
    }
    public record InvoicePage(List<Invoice> items, String nextCursor) { }
    public record Refund(String invoiceId,String status,Instant canceledAt) { }
    public record Accepted(String invoiceId, String status) { }
}
