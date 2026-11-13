package com.ipgo.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// #206 가입 규칙.
// - agreementVersion: 지금 이용약관 · 개인정보 수집 · 이용 동의서 버전. 문서를 바꾸면 프론트와 같이 올린다.
// - strict: false(전환 기간)면 동의 · 사업자등록번호가 없어도 가입된다. 보낸 경우에는 그 값을 검사한다.
//   프론트가 동의 화면을 배포한 뒤 true로 바꾸면 동의와 사업자등록번호(검증 숫자 포함)가 필수가 된다.
@ConfigurationProperties("ipgo.signup")
public record SignupProperties(@DefaultValue("2026-11-v1") String agreementVersion,
        @DefaultValue("false") boolean strict) {
}
