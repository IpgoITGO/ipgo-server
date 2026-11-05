package com.ipgo.server.infrastructure;

import com.ipgo.server.service.InsightGeneration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InsightWorkerTests {
    @Test void failureDoesNotStopDedicatedWorkerAndShutdownIsSafe() throws Exception {
        var generation=mock(InsightGeneration.class);
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        var second=new CountDownLatch(1);
        var thread=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            if(first.getAndSet(false)) throw new IllegalStateException("synthetic failure");
            second.countDown(); return null;
        }).when(generation).tick();
        var worker=new InsightWorker(generation,100);
        try {
            worker.start();
            assertThat(second.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).isEqualTo("insight-worker");
        } finally { worker.close(); }
    }
    @Test void excessivePollingIsRejected() {
        assertThatThrownBy(()->new InsightWorker(mock(InsightGeneration.class),0)).isInstanceOf(IllegalArgumentException.class);
    }
}
