package com.ipgo.server.infrastructure;

public interface NormalPaymentGateway {
    BillingGateway.Payment confirm(String paymentKey,String orderId,long amount,String key);
    BillingGateway.Payment lookup(String paymentKey);
    BillingGateway.Payment cancel(String paymentKey,String reason,String key);
}
