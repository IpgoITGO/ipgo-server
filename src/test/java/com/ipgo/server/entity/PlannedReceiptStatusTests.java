package com.ipgo.server.entity;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlannedReceiptStatusTests {
    final LocalDate today = LocalDate.of(2026, 11, 6);

    @Test void previouslyStoredOverdueCanReturnToPendingAfterDateEdit() {
        var receipt = new PlannedReceipt(1L, 2L, 5, today.minusDays(1));
        org.springframework.test.util.ReflectionTestUtils.setField(receipt, "status", "OVERDUE");
        receipt.update(null, today.plusDays(1));
        assertThat(receipt.statusOn(today)).isEqualTo("PENDING");
        assertThat(receipt.isReflectedOn(today)).isTrue();
        assertThat(receipt.isMatchable()).isTrue();
    }

    @Test void receivedAndCancelledWinOverExpiredDates() {
        var received = new PlannedReceipt(1L, 2L, 5, today.minusDays(1));
        assertThat(received.statusOn(today)).isEqualTo("OVERDUE");
        assertThat(received.isMatchable()).isTrue();
        received.receive(today);
        assertThat(received.statusOn(today.plusDays(10))).isEqualTo("RECEIVED");
        assertThat(received.isReflectedOn(today)).isFalse();
        assertThatThrownBy(() -> received.update(6L, today.plusDays(1)))
                .isInstanceOf(com.ipgo.server.exception.ProductException.class);
        var cancelled = new PlannedReceipt(1L, 2L, 5, today.minusDays(1));
        cancelled.cancel();
        assertThat(cancelled.statusOn(today)).isEqualTo("CANCELLED");
        assertThat(cancelled.isReflectedOn(today)).isFalse();
        assertThat(cancelled.isMatchable()).isFalse();
    }
}
