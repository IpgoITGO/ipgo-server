package com.ipgo.server.infrastructure;

import com.ipgo.server.config.BillingProperties;
import com.ipgo.server.exception.BillingException;
import com.ipgo.server.service.BillingRefundService;
import com.ipgo.server.service.BillingService;
import com.ipgo.server.service.NormalPaymentService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class BillingWorkerTests {
    @Test void unrelatedNormalOrderFailureDoesNotStopSubscriptionRefundRecovery() {
        var properties=mock(BillingProperties.class);
        when(properties.enabled()).thenReturn(true);
        when(properties.workerEnabled()).thenReturn(true);
        var billing=mock(BillingService.class);
        var refunds=mock(BillingRefundService.class);
        var normal=mock(NormalPaymentService.class);
        doThrow(BillingException.conflict("BILLING_MERCHANT_CHANGED")).when(normal).processOne();
        var worker=new BillingWorker(properties,billing,refunds,normal);
        try {
            worker.poll();
            verify(refunds).processOne();
            verify(refunds).checkPaidOne();
            verify(billing).prepareRenewals();
        } finally { worker.close(); }
    }
    @Test void disabledWorkerDoesNotPerformAnyWork() {
        var billing=mock(BillingService.class);
        var refunds=mock(BillingRefundService.class);
        var normal=mock(NormalPaymentService.class);
        var worker=new BillingWorker(mock(BillingProperties.class),billing,refunds,normal);
        try { worker.poll(); verifyNoInteractions(billing,refunds,normal); }
        finally { worker.close(); }
    }
}
