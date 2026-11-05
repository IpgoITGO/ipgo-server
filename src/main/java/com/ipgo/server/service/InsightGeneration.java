package com.ipgo.server.service;

import com.ipgo.server.infrastructure.InsightEngineClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Durable single-attempt work; never wait for HTTP while holding a result transaction. */
@Service
public class InsightGeneration {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final InsightRenderer renderer;
    private final InsightEngineClient client;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final Duration timeout;
    public InsightGeneration(JdbcTemplate jdbc, JsonMapper mapper, InsightRenderer renderer, InsightEngineClient client,
            TransactionTemplate tx, @Value("${ipgo.insight.enabled:false}") boolean enabled,
            @Value("${ipgo.insight.timeout:15s}") Duration timeout) {
        this.jdbc = jdbc; this.mapper = mapper; this.renderer = renderer; this.client = client;
        this.tx = tx; this.enabled = enabled; this.timeout = timeout;
    }

    /** Called inside the calculation transaction. A duplicate engine result never enqueues twice. */
    public void prepare(long resultId, JsonNode result, String asOf) {
        String status = "DISABLED", reason = "LLM_DISABLED";
        JsonNode payload = mapper.createObjectNode();
        if (enabled) {
            try {
                renderer.prepare(result, asOf); // Validate engine facts before normalizing to stored precision.
                var p = renderer.prepareStored(storedFacts(resultId, result), asOf);
                payload = mapper.valueToTree(Map.of("input", p.input(), "values", p.values(), "fallback", p.fallback()));
                status = "PENDING"; reason = null;
            } catch (IllegalArgumentException | ArithmeticException e) {
                status = "SKIPPED"; reason = "INPUT_UNAVAILABLE";
            }
        }
        jdbc.update("""
                INSERT INTO ai_explanations(result_id,mode,status,request_payload,validation_passed,reason_code,versions)
                VALUES (?, 'NONE', ?, CAST(? AS JSONB), FALSE, ?, CAST(? AS JSONB))
                """, resultId, status, mapper.writeValueAsString(payload), reason,
                mapper.writeValueAsString(Map.of("template", "spring-1", "llm", enabled ? "enabled" : "disabled")));
    }

    private JsonNode storedFacts(long id, JsonNode original) {
        var facts = (tools.jackson.databind.node.ObjectNode) original.deepCopy();
        String[] fields = {"horizonDays", "startStockQty", "plannedReceiptsInHorizon", "forecastOutboundQty",
                "multiplier", "recommendedQty", "preArrivalRisk", "riskSampleSize", "forecastRangeLow", "forecastRangeHigh"};
        jdbc.query("SELECT horizon_days,start_stock_qty,planned_receipt_qty,forecast_outbound_qty,multiplier,recommended_qty,pre_arrival_risk,risk_sample_size,forecast_range_low,forecast_range_high FROM calculation_results WHERE id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    for (int i=0; i<fields.length; i++) facts.set(fields[i], mapper.valueToTree(rs.getBigDecimal(i+1)));
                }, id);
        jdbc.query("SELECT scenario_type,scenario_qty,expected_ending_stock,unmet_demand_qty,post_arrival_risk FROM scenario_results WHERE result_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    for (JsonNode s : facts.path("scenarios")) if (rs.getString(1).equals(s.path("type").asText())) {
                        var scenario = (tools.jackson.databind.node.ObjectNode) s;
                        String[] names={"qty","expectedEndingStock","unmetDemandQty","postArrivalRisk"};
                        for(int i=0;i<names.length;i++) scenario.set(names[i],mapper.valueToTree(rs.getBigDecimal(i+2)));
                    }
                }, id);
        return facts;
    }

    public void tick() {
        if (!enabled) return;
        // Unknown outcome after a crash is terminal. Retrying could double charge the same request.
        for (Long id : jdbc.queryForList("SELECT result_id FROM ai_explanations WHERE status = 'PROCESSING' AND created_at < ? ORDER BY result_id FETCH FIRST 5 ROWS ONLY",
                Long.class, Timestamp.from(Instant.now().minus(timeout).minusSeconds(30)))) {
            expireClaimed(id);
        }
        List<Long> due = jdbc.queryForList("SELECT result_id FROM ai_explanations WHERE status = 'PENDING' ORDER BY result_id FETCH FIRST 1 ROWS ONLY", Long.class);
        for (Long id : due) {
            Boolean claimed = tx.execute(s -> jdbc.update("UPDATE ai_explanations SET status = 'PROCESSING', created_at = CURRENT_TIMESTAMP WHERE result_id = ? AND status = 'PENDING'", id) == 1);
            if (Boolean.TRUE.equals(claimed)) processClaimed(id);
        }
    }

    // Called only after a durable claim. The single-result path does not enable the global queue.
    void processClaimed(long id) {
        JsonNode p = payload(id);
        JsonNode response = null;
        String reason = null;
        try {
            response = client.generate(id, p.path("input"));
            validateResponse(response);
            if ("FAILED".equals(response.path("validation").asText())) reason = response.path("failReason").asText();
        } catch (InsightEngineClient.Failure e) { reason = e.reason(); }
        catch (RuntimeException e) { reason = "VALIDATION_FAILED"; response = null; }
        finish(id, p, response, reason);
    }

    void expireClaimed(long id) { finish(id, payload(id), null, "LLM_TIMEOUT"); }

    private JsonNode payload(long id) {
        return EngineJobs.readDatabaseJson(mapper, jdbc.queryForObject("SELECT request_payload FROM ai_explanations WHERE result_id = ?", String.class, id));
    }

    private void finish(long id, JsonNode payload, JsonNode response, String reason) {
        String rendered = null;
        String draft = null;
        boolean valid = false;
        if (reason == null && response != null) {
            try {
                Map<String, String> values = new java.util.LinkedHashMap<>();
                payload.path("values").properties().forEach(e -> values.put(e.getKey(), e.getValue().asText()));
                draft = response.path("draft").asText();
                rendered = renderer.render(draft, values);
                valid = true;
            } catch (RuntimeException e) { reason = "VALUE_MISMATCH"; }
        }
        if (!valid) rendered = payload.hasNonNull("fallback") ? payload.get("fallback").asText() : null;
        String mode = valid ? "LLM" : rendered == null ? "NONE" : "TEMPLATE";
        String version = response != null && response.path("promptVersion").isString() ? response.get("promptVersion").asText() : "unknown";
        // Only validated response metadata is stored; upstream bodies are never logged or returned in errors.
        boolean metadata = response != null && (reason == null || Set.of("LLM_ERROR", "LLM_TIMEOUT", "VALIDATION_FAILED", "VALUE_MISMATCH").contains(reason))
                && response.path("latencyMs").isIntegralNumber() && response.path("costUsd").isNumber();
        final String text = rendered, finalDraft = valid ? draft : null, finalReason = reason;
        final boolean passed = valid;
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE ai_explanations SET mode=?, status='DONE', rendered_text=?, raw_text=?, validation_passed=?,
                    inserted_values=CAST(? AS JSONB), reason_code=?, versions=CAST(? AS JSONB), latency_ms=?, cost_usd=?, created_at=CURRENT_TIMESTAMP
                WHERE result_id=? AND status='PROCESSING'
                """, mode, text, finalDraft, passed, mapper.writeValueAsString(payload.path("values")), finalReason,
                mapper.writeValueAsString(Map.of("template", "spring-1", "prompt", version)),
                metadata ? response.path("latencyMs").asInt() : null, metadata ? response.path("costUsd").decimalValue() : null, id));
    }

    private static void validateResponse(JsonNode r) {
        require(r != null && r.isObject());
        String validation = r.path("validation").asText();
        require(Set.of("PASSED", "FAILED").contains(validation));
        require(r.path("promptVersion").isString() && !r.path("promptVersion").asText().isBlank()
                && r.path("promptVersion").asText().length() <= 100 && !"mock".equalsIgnoreCase(r.path("promptVersion").asText()));
        require(r.path("latencyMs").isIntegralNumber() && r.path("latencyMs").canConvertToInt() && r.path("latencyMs").asInt() >= 0);
        require(r.path("costUsd").isNumber() && r.path("costUsd").decimalValue().signum() >= 0
                && r.path("costUsd").decimalValue().compareTo(new java.math.BigDecimal("999999.999999")) <= 0);
        if ("PASSED".equals(validation)) require(r.path("draft").isString() && !r.path("draft").asText().isBlank() && !r.hasNonNull("failReason"));
        else require(!r.hasNonNull("draft") && Set.of("LLM_ERROR", "LLM_TIMEOUT", "VALIDATION_FAILED").contains(r.path("failReason").asText()));
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid insight response"); }
}
