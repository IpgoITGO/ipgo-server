package com.ipgo.server.infrastructure;

public interface NormalPaymentGateway {
    record RefundAccount(String bank,String accountNumber,String holderName) {
        public RefundAccount {
            bank=bank==null?"":bank.strip(); accountNumber=accountNumber==null?"":accountNumber.strip(); holderName=holderName==null?"":holderName.strip();
            if(!bank.matches("[0-9]{2,3}") || !accountNumber.matches("[0-9]{1,20}") || holderName.isBlank() || holderName.length()>60 || holderName.chars().anyMatch(Character::isISOControl))
                throw com.ipgo.server.exception.BillingException.invalid();
        }
        @Override public String toString() { return "RefundAccount[redacted]"; }
    }
    BillingGateway.Payment confirm(String paymentKey,String orderId,long amount,String key);
    BillingGateway.Payment lookup(String paymentKey);
    BillingGateway.Payment cancel(String paymentKey,String reason,String key);
    default BillingGateway.Payment cancel(String paymentKey,String reason,String key,RefundAccount account) {
        if(account!=null) throw new BillingGateway.Failure(false,"REFUND_ACCOUNT_UNSUPPORTED");
        return cancel(paymentKey,reason,key);
    }
}
