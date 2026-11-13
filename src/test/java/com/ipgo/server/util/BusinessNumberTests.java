package com.ipgo.server.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// #206 사업자등록번호 정리와 국세청 검증 숫자
class BusinessNumberTests {
    @Test void normalizesDashesAndSpacesOnly() {
        assertThat(BusinessNumber.normalize("124-81-00998")).isEqualTo("1248100998");
        assertThat(BusinessNumber.normalize(" 124 81 00998 ")).isEqualTo("1248100998");
        assertThat(BusinessNumber.normalize("124-81-0099")).isNull();
        assertThat(BusinessNumber.normalize("12481009981")).isNull();
        assertThat(BusinessNumber.normalize("12a8100998")).isNull();
        assertThat(BusinessNumber.normalize(null)).isNull();
    }

    @Test void checksDigit() {
        assertThat(BusinessNumber.validChecksum("1248100998")).isTrue();   // 공개된 실제 번호
        assertThat(BusinessNumber.validChecksum("1234567891")).isTrue();
        assertThat(BusinessNumber.validChecksum("1234567890")).isFalse();
        assertThat(BusinessNumber.validChecksum("1248100997")).isFalse();
        assertThat(BusinessNumber.validChecksum("123456789")).isFalse();
    }
}
