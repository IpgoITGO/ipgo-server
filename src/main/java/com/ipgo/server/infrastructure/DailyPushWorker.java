package com.ipgo.server.infrastructure;

import com.ipgo.server.service.DailyPushService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ipgo.push.delivery.enabled", havingValue="true")
public class DailyPushWorker {
    private final DailyPushService service;
    private final long delay;
    private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("daily-push").factory());
    public DailyPushWorker(DailyPushService service,@Value("${ipgo.push.delivery.poll-delay-ms:60000}") long delay) {
        if (delay < 1000) throw new IllegalArgumentException("Push poll delay must be at least 1000ms");
        this.service=service; this.delay=delay;
    }
    @jakarta.annotation.PostConstruct public void start() { executor.scheduleWithFixedDelay(this::tick,delay,delay,TimeUnit.MILLISECONDS); }
    public void tick() {
        try { service.tick(); }
        catch (RuntimeException e) { LoggerFactory.getLogger(DailyPushWorker.class).warn("Daily push deferred; error_type={}",e.getClass().getSimpleName()); }
    }
    @jakarta.annotation.PreDestroy public void close() { executor.shutdownNow(); }
}
