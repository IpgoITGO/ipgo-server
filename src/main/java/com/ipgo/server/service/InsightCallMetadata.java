package com.ipgo.server.service;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/** Optional provider observations. Missing values are unknown, never an assumed zero. */
record InsightCallMetadata(String model, Integer inputTokens, Integer outputTokens, Integer latency, BigDecimal cost, String prompt) {
    static final InsightCallMetadata UNKNOWN = new InsightCallMetadata(null, null, null, null, null, null);
    static InsightCallMetadata read(JsonNode r) {
        if (r == null || !r.isObject()) throw new IllegalArgumentException("Invalid insight metadata");
        JsonNode prompt = r.path("promptVersion");
        if (!prompt.isString() || prompt.asText().isBlank() || prompt.asText().length() > 100
                || "mock".equalsIgnoreCase(prompt.asText())) throw new IllegalArgumentException("Invalid insight version");
        Integer latency = integer(r, "latencyMs");
        if (latency == null) throw new IllegalArgumentException("Missing insight latency");
        JsonNode model = r.path("modelName");
        if (!model.isMissingNode() && !model.isNull() && (!model.isString() || model.asText().isBlank()
                || model.asText().length() > 100 || model.asText().chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Invalid insight model");
        JsonNode cost = r.path("costUsd");
        if (cost.isMissingNode() || (!cost.isNull() && (!cost.isNumber() || cost.decimalValue().signum() < 0
                || cost.decimalValue().compareTo(new BigDecimal("999999.999999")) > 0)))
            throw new IllegalArgumentException("Invalid insight cost");
        return new InsightCallMetadata(model.isString() ? model.asText() : null,
                integer(r, "inputTokens"), integer(r, "outputTokens"), latency,
                cost.isNull() ? null : cost.decimalValue(), prompt.asText());
    }
    private static Integer integer(JsonNode r, String name) {
        JsonNode n = r.path(name);
        if (n.isMissingNode() || n.isNull()) return null;
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < 0)
            throw new IllegalArgumentException("Invalid insight count");
        return n.asInt();
    }
}
