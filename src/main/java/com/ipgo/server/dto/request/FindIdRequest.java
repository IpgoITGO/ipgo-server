package com.ipgo.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// #185 아이디 찾기. 회사명 · 담당자 이름은 가입 필수 칸이라 모든 계정을 찾을 수 있다.
// 사업자등록번호는 쇼핑몰에 공개되는 번호이고 가입 때 선택이라 쓰지 않는다.
public record FindIdRequest(@NotBlank @Size(max = 50) String companyName, @NotBlank @Size(max = 50) String managerName) {
}
