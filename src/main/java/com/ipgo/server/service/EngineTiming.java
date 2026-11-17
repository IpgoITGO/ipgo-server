package com.ipgo.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Opt-in, payload-free correlation for a single CALCULATE acceptance investigation. */
public final class EngineTiming {
    private static final Logger log = LoggerFactory.getLogger(EngineTiming.class);
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private EngineTiming() {}

    private static boolean safe(EngineJobs.Job job) {
        return job != null && "CALCULATE".equals(job.type()) && job.targetId() > 0
                && job.id() != null && job.id().matches(UUID_PATTERN);
    }

    public static void dispatch(boolean enabled, EngineJobs.Job job, long startNanos, boolean sent) {
        if (!enabled || !safe(job)) return;
        long millis = Math.max(0, System.nanoTime() - startNanos) / 1_000_000;
        log.info("{\"schemaVersion\":1,\"event\":\"server_calculation_timing\",\"phase\":\"dispatch\",\"jobId\":\"{}\",\"runId\":{},\"outcome\":\"{}\",\"durationMs\":{}}",
                job.id(), job.targetId(), sent ? "sent" : "send_failed", millis);
    }

    public enum Processing { UNKNOWN, SUCCEEDED, FAILED, DUPLICATE }

    public static final class ResultObservation {
        private Processing processing = Processing.UNKNOWN;
        public void processing(Processing value) { this.processing = value; }
    }

    /** Transaction duration includes parsing/validation, locking, writes and commit or rollback. */
    public static ResultObservation observeResult(boolean enabled, EngineJobs.Job job, long receivedNanos) {
        var observation = new ResultObservation();
        if (!enabled || !safe(job) || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) return observation;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                String transaction = switch (status) {
                    case STATUS_COMMITTED -> "committed";
                    case STATUS_ROLLED_BACK -> "rolled_back";
                    default -> "unknown";
                };
                long millis = Math.max(0, System.nanoTime() - receivedNanos) / 1_000_000;
                log.info("{\"schemaVersion\":1,\"event\":\"server_calculation_timing\",\"phase\":\"result_transaction\",\"jobId\":\"{}\",\"runId\":{},\"processing\":\"{}\",\"transaction\":\"{}\",\"durationMs\":{}}",
                        job.id(), job.targetId(), observation.processing.name().toLowerCase(java.util.Locale.ROOT), transaction, millis);
            }
        });
        return observation;
    }
}
