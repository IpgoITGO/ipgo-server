package com.ipgo.server.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed internal endpoint; no credentials or upstream response bodies are exposed in errors. */
@Component
public class InsightEngineClient {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final URI endpoint;
    private final Duration timeout;
    private final JsonMapper mapper;
    private final HttpClient client;

    public InsightEngineClient(@Value("${ipgo.engine.base-url:}") String base,
            @Value("${ipgo.insight.timeout:15s}") Duration timeout, JsonMapper mapper) {
        this.mapper = mapper;
        this.timeout = timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("Positive insight timeout required");
        URI origin = null;
        if (!base.isBlank()) {
            try {
                origin = URI.create(base);
                if (!("http".equalsIgnoreCase(origin.getScheme()) || "https".equalsIgnoreCase(origin.getScheme()))
                        || origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                        || origin.getFragment() != null || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Engine base URL must be an HTTP(S) origin without credentials");
            }
        }
        this.endpoint = origin == null ? null : origin.resolve("/engine/insight");
        this.client = origin == null ? null : HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static class Failure extends RuntimeException {
        private final String reason;
        public Failure(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }

    public JsonNode generate(long resultId, JsonNode input) {
        if (endpoint == null) throw new Failure("LLM_NOT_CONFIGURED");
        byte[] payload = mapper.writeValueAsBytes(Map.of("resultId", resultId, "input", input));
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        var pending = client.sendAsync(request, ignored -> new LimitedBody());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 501) throw new Failure("LLM_NOT_CONFIGURED");
            if (response.statusCode() == 422) throw new Failure("INVALID_INPUT");
            if (response.statusCode() == 503 || response.statusCode() == 429) throw new Failure("LLM_ERROR");
            if (response.statusCode() != 200) throw new Failure("LLM_ERROR");
            try {
                JsonNode body = mapper.readTree(response.body());
                if (body == null || !body.isObject()) throw new Failure("LLM_ERROR");
                return body;
            } catch (Failure e) { throw e; }
            catch (RuntimeException e) { throw new Failure("LLM_ERROR"); }
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new Failure("LLM_TIMEOUT");
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure("LLM_ERROR");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) throw new Failure("LLM_TIMEOUT");
            throw new Failure("LLM_ERROR");
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() { if (client != null) client.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean failed;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription s) { subscription = s; delegate.onSubscribe(s); }
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) size += buffer.remaining();
            if (size > MAX_RESPONSE_BYTES) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("Insight response limit exceeded"));
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable e) { if (!failed) delegate.onError(e); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
