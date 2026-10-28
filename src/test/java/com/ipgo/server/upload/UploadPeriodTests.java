package com.ipgo.server.upload;

import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadPeriodTests {
    @ParameterizedTest
    @ValueSource(strings = {"2026-09.xlsx", "수불부_2026_09.xlsx", "수불부 202609.XLSX", "2026.09 재고.xlsx",
            "2026년 9월 수불부.xlsx", "2026년09월.xlsx", "2026-09 2026-09.xlsx"})
    void readsYearAndMonthFromFileName(String fileName) {
        UploadPeriod period = UploadPeriod.fromFileName(fileName);

        assertThat(period.label()).isEqualTo("2026-09");
        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stock.xlsx", "2026-13.xlsx", "20260915.xlsx", "1999-09.xlsx", "2026-09_2026-10.xlsx"})
    void rejectsMissingInvalidOrSeveralPeriods(String fileName) {
        assertThatThrownBy(() -> UploadPeriod.fromFileName(fileName))
                .isInstanceOfSatisfying(UploadException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("UPLOAD_INVALID_FORMAT");
                    assertThat(exception.details()).asString().contains("fileName=" + fileName);
                });
    }
}
