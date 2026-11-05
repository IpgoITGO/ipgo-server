package com.ipgo.server.infrastructure;

import com.ipgo.server.service.InsightGeneration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Own single thread: an LLM timeout must not block calculation/SQS scheduling. */
@Component
@ConditionalOnProperty(name = {"ipgo.insight.enabled", "ipgo.insight.worker-enabled"}, havingValue = "true")
public class InsightWorker {
    private static final Logger log = LoggerFactory.getLogger(InsightWorker.class);
    private final InsightGeneration generation;
    private final long delay;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("insight-worker").daemon(true).factory());
    public InsightWorker(InsightGeneration generation, @Value("${ipgo.insight.poll-delay-ms:1000}") long delay) {
        if (delay < 100) throw new IllegalArgumentException("Insight poll delay must be at least 100ms");
        this.generation = generation; this.delay = delay;
    }
    @jakarta.annotation.PostConstruct
    public void start() { scheduler.scheduleWithFixedDelay(this::tick, delay, delay, TimeUnit.MILLISECONDS); }
    public void tick() {
        try { generation.tick(); }
        catch (RuntimeException e) { log.warn("Insight processing deferred; error_type={}", e.getClass().getSimpleName()); }
    }
    @jakarta.annotation.PreDestroy
    public void close() { scheduler.shutdownNow(); }
}
