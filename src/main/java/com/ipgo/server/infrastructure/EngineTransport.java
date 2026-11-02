package com.ipgo.server.infrastructure;

import com.ipgo.server.service.EngineJobs;
import com.ipgo.server.service.EngineResults;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/** One bounded scheduler; no DB connection is held while waiting for SQS. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = {"ipgo.engine.enabled", "ipgo.engine.transport-enabled"}, havingValue = "true")
public class EngineTransport {
    private static final Logger log = LoggerFactory.getLogger(EngineTransport.class);
    private final EngineJobs jobs;
    private final EngineResults results;
    private final String jobsUrl;
    private final String resultsUrl;
    private final SqsClient sqs;

    public EngineTransport(EngineJobs jobs, EngineResults results,
            @Value("${ipgo.engine.jobs-queue-url:}") String jobsUrl,
            @Value("${ipgo.engine.results-queue-url:}") String resultsUrl,
            @Value("${AWS_REGION:ap-northeast-2}") String region,
            @Value("${ipgo.engine.sqs-endpoint:}") String endpoint) {
        if (jobsUrl.isBlank() || resultsUrl.isBlank()) throw new IllegalArgumentException("Engine queues are required");
        this.jobs = jobs; this.results = results; this.jobsUrl = jobsUrl; this.resultsUrl = resultsUrl;
        var builder = SqsClient.builder().region(Region.of(region));
        if (!endpoint.isBlank()) builder.endpointOverride(java.net.URI.create(endpoint));
        this.sqs = builder
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(5)).apiCallTimeout(Duration.ofSeconds(12)).build()).build();
    }

    @Bean(destroyMethod = "close") SqsClient engineSqsClient() { return sqs; }

    @Scheduled(fixedDelayString = "${ipgo.engine.poll-delay-ms:5000}")
    public void tick() {
        try {
            for (String id : jobs.expired()) results.expire(id);
            for (var job : jobs.due()) {
                if (!jobs.claim(job.id())) continue;
                try {
                    sqs.sendMessage(r -> r.queueUrl(jobsUrl).messageBody(jobs.envelope(job)));
                    jobs.sent(job.id());
                } catch (Exception failure) { log.warn("Engine dispatch deferred; error_type={}", failure.getClass().getSimpleName()); }
            }
            var response = sqs.receiveMessage(r -> r.queueUrl(resultsUrl).maxNumberOfMessages(1).waitTimeSeconds(0).visibilityTimeout(120));
            for (var message : response.messages()) {
                try {
                    results.accept(message.body()); // DB commit occurs before returning through the proxy.
                    sqs.deleteMessage(r -> r.queueUrl(resultsUrl).receiptHandle(message.receiptHandle()));
                } catch (Exception failure) { log.warn("Engine result deferred; error_type={}", failure.getClass().getSimpleName()); }
            }
        } catch (Exception failure) { log.warn("Engine polling deferred; error_type={}", failure.getClass().getSimpleName()); }
    }
}
