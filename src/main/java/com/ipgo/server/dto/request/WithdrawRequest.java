package com.ipgo.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// #172 회원 탈퇴. 본인 확인용 현재 비밀번호.
public record WithdrawRequest(@NotBlank @Size(max = 100) String password) {
}
