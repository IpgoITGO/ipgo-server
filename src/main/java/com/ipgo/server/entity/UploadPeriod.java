package com.ipgo.server.entity;

import com.ipgo.server.exception.UploadException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

// 파일명에 기간이 있으면 우선한다. 없으면 업로드 단계에서 입고일자로 기간을 정한다.
public record UploadPeriod(YearMonth month) {
    private static final Pattern NUMERIC = Pattern.compile("(?<!\\d)(20\\d{2})[-_. ]?(0[1-9]|1[0-2])(?!\\d)");
    private static final Pattern KOREAN = Pattern.compile("(?<!\\d)(20\\d{2})\\s*년\\s*(0?[1-9]|1[0-2])\\s*월");

    public LocalDate start() { return month.atDay(1); }
    public LocalDate end() { return month.atEndOfMonth(); }
    public String label() { return month.toString(); }

    public static UploadPeriod fromFileName(String fileName) {
        return findInFileName(fileName).orElseThrow(() ->
                invalidFormat(fileName, "파일명에서 연·월을 찾지 못했어요. 예: 2026-09.xlsx"));
    }

    public static Optional<UploadPeriod> findInFileName(String fileName) {
        String base = fileName.replaceFirst("(?i)\\.xlsx$", "");
        Set<YearMonth> found = new LinkedHashSet<>();
        collect(NUMERIC.matcher(base), found);
        collect(KOREAN.matcher(base), found);
        if (found.size() > 1) throw invalidFormat(fileName, "파일명에 연·월이 여러 개 있어요. 한 달씩 올려주세요.");
        return found.stream().findFirst().map(UploadPeriod::new);
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
