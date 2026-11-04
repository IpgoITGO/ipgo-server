package com.ipgo.server.controller;

import com.ipgo.server.dto.request.PlannedReceiptUpdateRequest;
import com.ipgo.server.dto.response.PlannedReceiptItem;
import com.ipgo.server.service.PlannedReceiptService;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannedReceiptRunHeaderTests {
    final PlannedReceiptService service = mock(PlannedReceiptService.class);
    final PlannedReceiptController controller = new PlannedReceiptController(service);
    final Principal owner = () -> "7";

    @Test void patchKeepsBodyAndAddsRunHeader() {
        var request = new PlannedReceiptUpdateRequest(10L, null);
        var item = new PlannedReceiptItem(1L, 3L, "합성 상품", 10L, null, null, "NO_DATE", false);
        when(service.update(7, 1, request)).thenReturn(new PlannedReceiptService.Changed(item, 71L));
        var response = controller.update(owner, 1, request);
        assertEquals(200, response.getStatusCode().value());
        assertSame(item, response.getBody());
        assertEquals("71", response.getHeaders().getFirst("X-Calculation-Run-Id"));
    }

    @Test void cancelKeeps204WithoutBodyAndAddsRunHeader() {
        when(service.cancel(7, 1)).thenReturn(72L);
        var response = controller.cancel(owner, 1);
        assertEquals(204, response.getStatusCode().value());
        assertNull(response.getBody());
        assertEquals("72", response.getHeaders().getFirst("X-Calculation-Run-Id"));
        verify(service, times(1)).cancel(7, 1);
    }

    @Test void noCalculationDoesNotInventHeader() {
        when(service.cancel(7, 1)).thenReturn(null);
        assertNull(controller.cancel(owner, 1).getHeaders().getFirst("X-Calculation-Run-Id"));
        var request = new PlannedReceiptUpdateRequest(10L, null);
        when(service.update(7, 1, request)).thenReturn(new PlannedReceiptService.Changed(null, null));
        assertNull(controller.update(owner, 1, request).getHeaders().getFirst("X-Calculation-Run-Id"));
    }
}
