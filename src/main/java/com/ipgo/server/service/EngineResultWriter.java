package com.ipgo.server.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Called only within the result transaction, after locking its warehouse and job. */
@Service
public class EngineResultWriter {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final InsightGeneration insights;
    public EngineResultWriter(JdbcTemplate jdbc, JsonMapper mapper, InsightGeneration insights) {
        this.jdbc = jdbc; this.mapper = mapper; this.insights = insights;
    }

    public void complete(EngineJobs.Job job, JsonNode response) {
        var row = jdbc.queryForObject("SELECT job_id,status,selection_snapshot FROM calculation_runs WHERE id = ? AND warehouse_id = ? FOR UPDATE",
                (rs, n) -> Map.of("job_id", rs.getString(1), "status", rs.getString(2), "selection_snapshot", rs.getString(3)),
                job.targetId(), job.warehouseId());
        require(job.id().equals(row.get("job_id")));
        if (Set.of("DONE", "FAILED").contains(row.get("status"))) return;
        JsonNode snapshot = EngineJobs.readDatabaseJson(mapper, row.get("selection_snapshot").toString());
        JsonNode input = snapshot.get("request");
        require(input != null && input.equals(job.payload()));
        String version = text(response, "engineVersion", 50, true);
        JsonNode results = response.get("results");
        require(results != null && results.isArray());
        Set<Long> expected = ids(input.get("productIds")), seen = new HashSet<>();
        var facts = ((tools.jackson.databind.node.ObjectNode) snapshot).putObject("resultFacts");
        for (JsonNode result : results) {
            long productId = positiveId(result.get("productId"));
            require(expected.contains(productId) && seen.add(productId));
            save(job, productId, input, result);
            var evaluation = AccuracyEvaluationFacts.validate(result, input);
            var fact = facts.putObject("" + productId);
            if (evaluation != null) fact.set("accuracyEvaluation", evaluation);
            var beforeRounding = number(result, "requiredQtyBeforeRounding", false);
            if (beforeRounding != null) fact.put("requiredQtyBeforeRounding", beforeRounding);
        }
        require(seen.equals(expected));
        var excluded = snapshot.get("excluded");
        if (excluded != null) {
            for (var entry : excluded.properties()) {
                long productId = Long.parseLong(entry.getKey());
                require(!seen.contains(productId) && ids(snapshot.get("productIds")).contains(productId));
                var result = mapper.createObjectNode().put("status", "EXCLUDED").put("reason", entry.getValue().asText());
                save(job, productId, input, result);
                seen.add(productId);
            }
        }
        require(seen.equals(ids(snapshot.get("productIds"))));
        jdbc.update("UPDATE calculation_runs SET status = 'DONE', engine_version = ?, selection_snapshot = CAST(? AS JSONB), completed_at = CURRENT_TIMESTAMP WHERE id = ?",
                version, mapper.writeValueAsString(snapshot), job.targetId());
    }

    public static final String FAILED_MESSAGE = "계산을 완료하지 못했습니다. 입력과 서버 상태를 확인하고 다시 시도해 주세요.";
    public static final String TIMEOUT_MESSAGE = "계산이 오래 걸려 멈췄어요. 다시 계산해 주세요.";

    public void fail(EngineJobs.Job job) { fail(job, FAILED_MESSAGE); }

    public void fail(EngineJobs.Job job, String message) {
        jdbc.update("""
                UPDATE calculation_runs SET status = 'FAILED', error_message = ?, completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND job_id = ? AND status IN ('QUEUED','RUNNING')
                """, message, job.targetId(), job.id());
    }

    private void save(EngineJobs.Job job, long productId, JsonNode input, JsonNode r) {
        // A result cannot target another tenant even if the message or snapshot is malformed.
        require(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = ? AND user_id = ?", Long.class,
                productId, positiveId(input.get("userId"))) == 1);
        String status = text(r, "status", 20, true);
        require(Set.of("OK", "NOT_NEEDED", "NOT_CALCULABLE", "EXCLUDED", "REUPLOAD_REQUIRED").contains(status));
        String reason = text(r, "reason", 2000, false);
        if (Set.of("NOT_CALCULABLE","EXCLUDED","REUPLOAD_REQUIRED").contains(status)) require(reason != null && !reason.isBlank());
        BigDecimal recommended = number(r, "recommendedQty", true);
        if (Set.of("OK", "NOT_NEEDED").contains(status)) require(recommended != null);
        if ("NOT_NEEDED".equals(status)) require(recommended.signum() == 0);
        if ("OK".equals(status)) require(recommended.signum() > 0);
        if (!Set.of("OK", "NOT_NEEDED").contains(status)) require(recommended == null);
        String method = text(r, "method", 10, false);
        require(method == null || Set.of("A","B").contains(method));
        LocalDate asOf = LocalDate.parse(input.get("asOfDate").asText()), today = LocalDate.parse(input.get("today").asText());
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("run_id", job.targetId()); values.put("product_id", productId);
        values.put("origin_date", asOf); values.put("start_date", today);
        values.put("method", method); values.put("method_reason", text(r, "methodReason", 255, false));
        values.put("multiplier", r.hasNonNull("multiplier") ? number(r,"multiplier",false) : new BigDecimal("1.0"));
        values.put("horizon_days", r.hasNonNull("horizonDays") ? integer(r,"horizonDays") : 0);
        for (String[] field : List.of(
                new String[]{"start_stock_qty","startStockQty"}, new String[]{"planned_receipt_qty","plannedReceiptsInHorizon"},
                new String[]{"forecast_outbound_qty","forecastOutboundQty"}, new String[]{"pre_arrival_unmet_qty","preArrivalUnmetQty"},
                new String[]{"recommended_qty","recommendedQty"}, new String[]{"recommended_amount","recommendedAmount"},
                new String[]{"pre_arrival_risk","preArrivalRisk"}, new String[]{"risk_sample_size","riskSampleSize"},
                new String[]{"valid_observation_days","validObservationDays"}, new String[]{"cover_success_count","coverSuccessCount"},
                new String[]{"cover_trial_count","coverTrialCount"}, new String[]{"forecast_error_rate","forecastErrorRate"},
                new String[]{"forecast_range_low","forecastRangeLow"}, new String[]{"forecast_range_high","forecastRangeHigh"})) {
            boolean integral = Set.of("planned_receipt_qty","recommended_qty","recommended_amount","risk_sample_size",
                    "valid_observation_days","cover_success_count","cover_trial_count").contains(field[0]);
            values.put(field[0], number(r,field[1],integral));
        }
        values.put("expected_shortage_on", date(r,"expectedShortageOn"));
        values.put("status", status); values.put("reason", reason);
        String grade = text(r,"accuracyGrade",10,false);
        require(grade == null || Set.of("HIGH","MEDIUM","LOW").contains(grade));
        values.put("accuracy_grade", grade);
        String riskBasis = text(r, "riskBasis", 10, false);
        require(riskBasis == null || Set.of("SKU", "POOLED").contains(riskBasis));
        values.put("risk_basis", riskBasis);
        var one = input.deepCopy();
        var object = (tools.jackson.databind.node.ObjectNode) one;
        object.putArray("productIds").add(productId);
        var oneSettings = object.putArray("settings");
        for (var setting : input.get("settings")) if (positiveId(setting.get("productId")) == productId) oneSettings.add(setting);
        var oneReceipts = object.putArray("plannedReceipts");
        for (var receipt : input.get("plannedReceipts")) if (positiveId(receipt.get("productId")) == productId) oneReceipts.add(receipt);
        values.put("input_snapshot", mapper.writeValueAsString(one));
        var args = new ArrayList<>(values.values());
        String parameters = values.keySet().stream().map(k -> k.equals("input_snapshot") ? "CAST(? AS JSONB)" : "?")
                .collect(java.util.stream.Collectors.joining(","));
        jdbc.update("INSERT INTO calculation_results("+String.join(",",values.keySet())+") VALUES ("+parameters+")", args.toArray());
        long resultId = jdbc.queryForObject("SELECT id FROM calculation_results WHERE run_id = ? AND product_id = ?", Long.class,
                job.targetId(), productId);
        JsonNode scenarios = r.get("scenarios");
        if (scenarios == null) { require(!"OK".equals(status)); return; }
        require(scenarios.isArray() && scenarios.size() <= 3);
        if (!"OK".equals(status)) require(scenarios.isEmpty());
        Set<String> types = new HashSet<>();
        for (JsonNode scenario : scenarios) {
            String type = text(scenario,"type",10,true);
            require(Set.of("P80","P100","P120").contains(type) && types.add(type));
            BigDecimal qty = number(scenario,"qty",true), ending = number(scenario,"expectedEndingStock",false), unmet = number(scenario,"unmetDemandQty",false);
            require(qty != null && ending != null && unmet != null && scenario.get("isDuplicate").isBoolean());
            if ("P100".equals(type)) require(qty.compareTo(recommended) == 0);
            var projection = scenario.get("dailyProjection");
            require(projection != null && projection.isArray() && projection.size() <= integer(r,"horizonDays"));
            LocalDate last = today;
            for (var day : projection) {
                LocalDate current = date(day,"date");
                require(current != null && current.isAfter(last) && !current.isAfter(today.plusDays(integer(r,"horizonDays"))));
                require(number(day,"closingQty",false) != null);
                last = current;
            }
            jdbc.update("""
                    INSERT INTO scenario_results(result_id,scenario_type,scenario_qty,is_duplicate,expected_depletion_on,
                        expected_ending_stock,unmet_demand_qty,post_arrival_risk,daily_projection)
                    VALUES (?,?,?,?,?,?,?,?,CAST(? AS JSONB))
                    """, resultId,type,qty,scenario.get("isDuplicate").asBoolean(),date(scenario,"expectedDepletionOn"),
                    ending,unmet,number(scenario,"postArrivalRisk",false),mapper.writeValueAsString(projection));
        }
        if ("OK".equals(status)) {
            require(types.equals(Set.of("P80","P100","P120")));
            insights.prepare(resultId, r, asOf.toString());
        }
    }

    private static Set<Long> ids(JsonNode array) {
        require(array != null && array.isArray());
        Set<Long> ids = new HashSet<>();
        for (var id : array) require(ids.add(positiveId(id)));
        return ids;
    }
    private static long positiveId(JsonNode n) { require(n != null && n.isIntegralNumber() && n.canConvertToLong() && n.asLong() > 0); return n.asLong(); }
    private static int integer(JsonNode n, String key) {
        var value = number(n,key,true); require(value != null && value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0); return value.intValueExact();
    }
    private static BigDecimal number(JsonNode node, String key, boolean integral) {
        JsonNode n = node.get(key);
        if (n == null || n.isNull()) return null;
        require(n.isNumber());
        BigDecimal value = n.decimalValue();
        require(value.signum() >= 0 && value.compareTo(new BigDecimal("999999999999999")) <= 0);
        if (integral) require(value.stripTrailingZeros().scale() <= 0);
        return value;
    }
    private static LocalDate date(JsonNode node, String key) {
        String text = text(node,key,10,false); return text == null ? null : LocalDate.parse(text);
    }
    private static String text(JsonNode node, String key, int max, boolean required) {
        require(node != null && node.isObject());
        JsonNode n = node.get(key);
        if (n == null || n.isNull()) { require(!required); return null; }
        require(n.isString() && n.asText().length() <= max && (!required || !n.asText().isBlank())); return n.asText();
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid engine result"); }
}
