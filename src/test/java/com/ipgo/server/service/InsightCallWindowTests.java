package com.ipgo.server.service;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class InsightCallWindowTests {
    private InsightCallWindow window(String now) {
        return new InsightCallWindow("2026-11-06T06:30:00Z", "2026-11-06T08:30:00Z",
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }
    @Test void opensAtStartButClosesExactlyAtDeadline() {
        assertThat(window("2026-11-06T06:29:59Z").open()).isFalse();
        assertThat(window("2026-11-06T06:30:00Z").open()).isTrue();
        assertThat(window("2026-11-06T08:29:59Z").open()).isTrue();
        assertThat(window("2026-11-06T08:30:00Z").open()).isFalse();
        assertThat(window("2026-11-06T08:30:01Z").open()).isFalse();
    }
    @Test void rejectsMalformedAndInvertedWindows() {
        assertThatThrownBy(() -> new InsightCallWindow("", "bad")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new InsightCallWindow("2026-11-07T00:00:00Z", "2026-11-06T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void continuousWindowStillExcludesHistoricalCalculations() {
        var window=new InsightCallWindow("2026-11-06T06:30:00Z", "",
                Clock.fixed(Instant.parse("2026-12-07T00:00:00Z"),ZoneOffset.UTC));
        assertThat(window.open()).isTrue();
        assertThat(window.limited()).isTrue();
        assertThat(window.startsAt()).isEqualTo(Instant.parse("2026-11-06T06:30:00Z"));
    }
}
