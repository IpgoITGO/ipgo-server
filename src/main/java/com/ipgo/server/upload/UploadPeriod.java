package com.ipgo.server.upload;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

// 파일명에 적힌 연·월이 자료 기간이다. 예: 2026-09.xlsx, 수불부_202609.xlsx, 2026년 9월.xlsx → 2026-09-01 ~ 2026-09-30
public record UploadPeriod(YearMonth month) {
    private static final Pattern NUMERIC = Pattern.compile("(?<!\\d)(20\\d{2})[-_. ]?(0[1-9]|1[0-2])(?!\\d)");
    private static final Pattern KOREAN = Pattern.compile("(?<!\\d)(20\\d{2})\\s*년\\s*(0?[1-9]|1[0-2])\\s*월");

    public LocalDate start() { return month.atDay(1); }
    public LocalDate end() { return month.atEndOfMonth(); }
    public String label() { return month.toString(); }

    static UploadPeriod fromFileName(String fileName) {
        String base = fileName.replaceFirst("(?i)\\.xlsx$", "");
        Set<YearMonth> found = new LinkedHashSet<>();
        collect(NUMERIC.matcher(base), found);
        collect(KOREAN.matcher(base), found);
        if (found.isEmpty()) throw invalidFormat(fileName, "파일명에서 연·월을 찾지 못했어요. 예: 2026-09.xlsx");
        if (found.size() > 1) throw invalidFormat(fileName, "파일명에 연·월이 여러 개 있어요. 한 달씩 올려주세요.");
        return new UploadPeriod(found.iterator().next());
    }

    private static void collect(Matcher matcher, Set<YearMonth> found) {
        while (matcher.find()) {
            found.add(YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
        }
    }

    private static UploadException invalidFormat(String fileName, String reason) {
        return new UploadException(HttpStatus.BAD_REQUEST, "UPLOAD_INVALID_FORMAT", reason,
                Map.of("fileName", fileName, "reason", reason));
    }
}
