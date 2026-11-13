package com.ipgo.server.util;

// #206 사업자등록번호. 하이픈 · 공백은 빼고 숫자 10자리로 비교한다.
// 마지막 자리는 국세청 검증 숫자다: 앞 9자리에 가중치 1,3,7,1,3,7,1,3,5를 곱해 더하고,
// 9번째 자리 × 5의 십의 자리를 더한 합의 (10 - 일의 자리) % 10이 마지막 자리와 같아야 한다.
public final class BusinessNumber {
    private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

    private BusinessNumber() { }

    // 숫자만 남긴다. 숫자 · 하이픈 · 공백 외의 글자가 있거나 10자리가 아니면 null
    public static String normalize(String raw) {
        if (raw == null) return null;
        if (!raw.matches("[0-9\\- ]+")) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() == 10 ? digits : null;
    }

    public static boolean validChecksum(String digits) {
        if (digits == null || !digits.matches("[0-9]{10}")) return false;
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (digits.charAt(i) - '0') * WEIGHTS[i];
        sum += (digits.charAt(8) - '0') * 5 / 10;
        return (10 - sum % 10) % 10 == digits.charAt(9) - '0';
    }
}
