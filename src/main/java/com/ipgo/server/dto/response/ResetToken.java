package com.ipgo.server.dto.response;

// #173 코드 확인 뒤 새 비밀번호를 저장할 때 쓰는 일회용 토큰(30분)
public record ResetToken(String resetToken) {
}
