package com.ipgo.server.dto.response;

import java.util.List;

// #185 아이디 찾기 결과. 아이디는 가입 이메일이라 앞 두 글자만 보이게 가린다(예: se***@a.com).
// maskedEmails는 맞는 계정 전부(최근 가입 순, 최대 5개), maskedEmail은 그중 가장 최근 1개다(기존 화면 호환용).
public record FoundAccounts(List<String> maskedEmails, String maskedEmail) {
}
