package com.ipgo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// #208 결제 완료 후 회원 직접 환불 제한(약관 제16조).
// - restricted=false(기본, 전환 기간): 지금처럼 회원이 결제 취소 · 환불을 요청할 수 있고, 결제 동의 버전도 지금 것(v1)이다.
// - restricted=true: 결제 완료 건의 새 환불 요청은 409 REFUND_NOT_ALLOWED, 결제 동의 버전은 환불 제한 고지가 들어간 v2다.
//   프론트가 결제 화면 고지를 넣고 환불 버튼을 뺀 배포와 같이 켠다. 이미 진행 중인 환불과 입금 전 가상계좌 취소는 그대로다.
@ConfigurationProperties("ipgo.payment.refund-policy")
public record RefundPolicyProperties(@DefaultValue("false") boolean restricted) {
    public static final String CONSENT_BEFORE = "one-time-month-2026-11-v1";
    public static final String CONSENT_NO_REFUND = "one-time-month-2026-11-v2";

    // 지금 결제 화면이 보내야 하는 결제 동의 버전
    public String consentVersion() {
        return restricted ? CONSENT_NO_REFUND : CONSENT_BEFORE;
    }
}
