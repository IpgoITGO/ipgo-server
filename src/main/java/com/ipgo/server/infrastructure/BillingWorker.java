package com.ipgo.server.infrastructure;

import com.ipgo.server.config.BillingProperties;
import com.ipgo.server.service.BillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class BillingWorker {
    private static final Logger log=LoggerFactory.getLogger(BillingWorker.class);
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("billing-worker").factory());
    private final BillingProperties properties;
    private final BillingService service;
    private final com.ipgo.server.service.BillingRefundService refunds;
    private final com.ipgo.server.service.NormalPaymentService normal;
    public BillingWorker(BillingProperties p,BillingService s,com.ipgo.server.service.BillingRefundService refunds,com.ipgo.server.service.NormalPaymentService normal) { properties=p; service=s; this.refunds=refunds; this.normal=normal; }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if(properties.enabled() && properties.workerEnabled()) executor.scheduleWithFixedDelay(this::poll,0,60,TimeUnit.SECONDS);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
    public void poll() {
        if(!properties.enabled() || !properties.workerEnabled()) return;
        try { normal.processOne(); refunds.processOne(); service.prepareRenewals(); service.processOne(); service.retireOneMethod(); }
        catch(Exception e) { log.warn("Billing worker requires reconciliation; exception type={}",e.getClass().getSimpleName()); }
    }
}
