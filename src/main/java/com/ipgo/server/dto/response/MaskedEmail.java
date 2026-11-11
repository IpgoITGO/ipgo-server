package com.ipgo.server.dto.response;

// #173 아이디 찾기 결과. 아이디는 가입 이메일이라 앞 두 글자만 보이게 가린다(예: se***@a.com).
public record MaskedEmail(String maskedEmail) {
}
