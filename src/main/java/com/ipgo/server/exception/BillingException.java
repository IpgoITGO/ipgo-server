package com.ipgo.server.exception;

public class BillingException extends RuntimeException {
    private final int status;
    private final String code;
    public BillingException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
    public static BillingException conflict(String code) { return new BillingException(409,code,"현재 결제 상태를 확인한 뒤 다시 요청해주세요."); }
    public static BillingException invalid() { return new BillingException(400,"INVALID_INPUT","결제 요청 값을 확인해주세요."); }
    public static BillingException forbidden() { return new BillingException(403,"FORBIDDEN","본인 결제 정보만 사용할 수 있습니다."); }
}
