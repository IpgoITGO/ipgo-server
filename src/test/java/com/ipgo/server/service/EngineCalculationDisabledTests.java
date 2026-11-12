package com.ipgo.server.service;

import com.ipgo.server.repository.WarehouseRepository;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class EngineCalculationDisabledTests {
    @Test void disabledEngineDoesNotReadClockOrWarehouseForEitherEntryPoint() {
        var jobs = mock(EngineJobs.class);
        when(jobs.enabled()).thenReturn(false);
        var warehouses = mock(WarehouseRepository.class);
        var clock = mock(Clock.class);
        var billing = mock(BillingService.class);
        // 비활성 경로는 자료/설정/DB 의존성 없이도 계산을 건너뛰어야 한다.
        var service = new EngineCalculationService(jobs, warehouses, null, null, null,
                null, null, null, null, clock, billing, null);

        assertNull(service.request(7, 9, "SETTINGS", false));
        assertNull(service.request(7, 9, "RECEIPTS", false, LocalDate.of(2026, 11, 6)));
        verifyNoInteractions(clock, warehouses, billing);
    }
}
