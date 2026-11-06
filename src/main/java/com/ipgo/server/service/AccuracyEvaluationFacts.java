package com.ipgo.server.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** #93: 계산과 같은 과거 재생 이력만 원본 소수로 저장한다. */
final class AccuracyEvaluationFacts {
    private AccuracyEvaluationFacts() { }
    private static final Set<String> FIELDS = Set.of("schemaVersion", "status", "reason", "periodStart", "periodEnd",
            "observedThrough", "horizonDays", "method", "multiplier", "policyVersion", "policyHash", "dataHash", "history");

    static JsonNode validate(JsonNode result, JsonNode input) {
        JsonNode e = result.get("accuracyEvaluation");
        if (e == null || e.isNull()) return null; // 이전 AI와 호환
        require(e.isObject() && e.path("schemaVersion").isIntegralNumber() && e.path("schemaVersion").canConvertToInt() && e.path("schemaVersion").asInt() == 1);
        e.properties().forEach(f -> require(FIELDS.contains(f.getKey())));
        for (String key : Set.of("reason","periodStart","periodEnd","method","policyVersion","policyHash")) {
            var value = e.path(key);
            require(value.isMissingNode() || value.isNull() || value.isString() && value.asText().length() <= 64);
        }
        require(e.path("horizonDays").isNull() || e.path("horizonDays").isMissingNode()
                || e.path("horizonDays").isIntegralNumber() && e.path("horizonDays").canConvertToInt());
        for (String key : Set.of("periodStart","periodEnd")) if (e.path(key).isString()) {
            LocalDate periodDate = date(e,key);
            require(periodDate.equals(LocalDate.of(2026,key.equals("periodStart") ? 5 : 7,key.equals("periodStart") ? 1 : 31)));
        }
        require(e.path("periodStart").isString() == e.path("periodEnd").isString());
        if (e.path("horizonDays").isIntegralNumber()) require(e.path("horizonDays").asInt() > 0
                && e.path("horizonDays").equals(result.path("horizonDays")));
        if (e.path("method").isString()) require(Set.of("A","B").contains(e.path("method").asText())
                && e.path("method").equals(result.path("method")));
        require(e.path("dataHash").asText().matches("[a-f0-9]{64}"));
        LocalDate cutoff = date(e, "observedThrough");
        require(cutoff.equals(date(input, "asOfDate")));
        require(number(e, "multiplier").compareTo(number(input.path("policy"), "multiplier")) == 0);
        JsonNode history = e.path("history");
        require(history.isArray() && history.size() <= 13);
        String state = e.path("status").asText();
        require(Set.of("AVAILABLE", "EMPTY", "UNAVAILABLE").contains(state));
        if (state.equals("UNAVAILABLE")) {
            require(history.isEmpty() && e.path("reason").asText().equals("EVALUATION_UNAVAILABLE"));
            require(result.path("coverTrialCount").isNull() || result.path("coverTrialCount").isMissingNode());
            require(result.path("coverSuccessCount").isNull() || result.path("coverSuccessCount").isMissingNode());
            require(result.path("forecastErrorRate").isNull() || result.path("forecastErrorRate").isMissingNode());
            return e.deepCopy();
        }
        require(Set.of("OK", "NOT_NEEDED").contains(result.path("status").asText()));
        require(e.path("policyVersion").asText().equals("da07-2026-tune-weekly-v1"));
        require(e.path("policyHash").asText().equals("8594706942cd7bca0c7e73cabf4c242d02d9d731eaa1d23461038e6118f6ee6d"));
        LocalDate start = date(e,"periodStart"), end = date(e,"periodEnd");
        require(start.equals(LocalDate.of(2026,5,1)) && end.equals(LocalDate.of(2026,7,31)));
        require(!date(input,"today").isBefore(LocalDate.of(2026,11,3)));
        require(e.path("horizonDays").isIntegralNumber() && e.path("horizonDays").canConvertToInt());
        int horizon = e.path("horizonDays").asInt();
        require(horizon > 0 && horizon == result.path("horizonDays").asInt());
        JsonNode setting = null;
        for (var candidate : input.path("settings")) if (candidate.path("productId").asLong() == result.path("productId").asLong()) setting = candidate;
        require(setting != null && horizon == setting.path("leadTimeDays").asInt() + setting.path("reviewCycleDays").asInt());
        require(Set.of("A","B").contains(e.path("method").asText()) && e.path("method").equals(result.path("method")));
        LocalDate previous = null;
        int covered = 0;
        BigDecimal actual = BigDecimal.ZERO, error = BigDecimal.ZERO;
        for (var point : history) {
            require(point.isObject() && point.size() == 4);
            point.properties().forEach(f -> require(Set.of("originDate","forecastQty","actualQty","covered").contains(f.getKey())));
            LocalDate origin = date(point,"originDate");
            require(!origin.isBefore(start) && !origin.isAfter(LocalDate.of(2026,7,24)) && ChronoUnit.DAYS.between(start,origin) % 7 == 0);
            require(previous == null || origin.isAfter(previous));
            require(!origin.plusDays(horizon).isAfter(end) && !origin.plusDays(horizon).isAfter(cutoff));
            previous = origin;
            var forecast = number(point,"forecastQty"); var observed = number(point,"actualQty");
            actual = actual.add(observed); error = error.add(forecast.subtract(observed).abs());
            require(point.path("covered").isBoolean());
            if (point.path("covered").asBoolean()) covered++;
        }
        if (history.isEmpty()) {
            require(state.equals("EMPTY") && e.path("reason").asText().equals("NO_VALID_SAMPLES"));
            require(result.path("coverTrialCount").isNull() || result.path("coverTrialCount").isMissingNode());
            require(result.path("coverSuccessCount").isNull() || result.path("coverSuccessCount").isMissingNode());
        } else {
            require(state.equals("AVAILABLE") && e.path("reason").isNull());
            require(result.path("coverTrialCount").isIntegralNumber() && result.path("coverTrialCount").canConvertToInt() && result.path("coverTrialCount").asInt() == history.size());
            require(result.path("coverSuccessCount").isIntegralNumber() && result.path("coverSuccessCount").canConvertToInt() && result.path("coverSuccessCount").asInt() == covered);
        }
        if (actual.signum() == 0) require(result.path("forecastErrorRate").isNull() || result.path("forecastErrorRate").isMissingNode());
        else {
            var expected = error.divide(actual, MathContext.DECIMAL128);
            require(number(result,"forecastErrorRate").subtract(expected).abs().compareTo(
                    expected.abs().max(BigDecimal.ONE).multiply(new BigDecimal("0.000000001"))) <= 0);
        }
        return e.deepCopy();
    }
    private static LocalDate date(JsonNode node, String key) {
        require(node.path(key).isString()); return LocalDate.parse(node.path(key).asText());
    }
    private static BigDecimal number(JsonNode node, String key) {
        require(node.path(key).isNumber());
        var value = node.path(key).decimalValue();
        require(value.signum() >= 0 && value.compareTo(new BigDecimal("999999999999999")) <= 0);
        return value;
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid accuracy evaluation"); }
}
