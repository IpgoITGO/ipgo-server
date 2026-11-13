package com.ipgo.server.service;

import com.ipgo.server.util.DisplayQuantities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Values stay in Spring. Only an alias, key names and comparison directions leave the server. */
@Component
public class InsightRenderer {
    private static final Pattern KEY = Pattern.compile("\\{\\{([a-z][a-z0-9_]*)\\}\\}");
    private static final Set<String> INPUT_FIELDS = Set.of("recommended", "placeholders", "directions", "riskStatus",
            "scenarios", "methodFallback", "startStockEstimated", "hasNoDateReceipts");
    public record Prepared(JsonNode input, Map<String, String> values, String fallback) { }
    private final JsonMapper mapper;
    public InsightRenderer(JsonMapper mapper) { this.mapper = mapper; }

    public Prepared prepare(JsonNode r, String asOf) { return prepare(r, asOf, true); }
    Prepared prepareStored(JsonNode r, String asOf) { return prepare(r, asOf, false); }
    /** Historical inputs come exclusively from persisted calculation facts, never an HTTP body. */
    Prepared prepareHistorical(JsonNode facts, String asOf) {
        var r = (tools.jackson.databind.node.ObjectNode) facts.deepCopy();
        var source = r.putObject("insightInput");
        source.put("recommended", "P100");
        for (String key : List.of("riskStatus", "methodFallback", "startStockEstimated")) source.set(key, r.get(key));
        source.put("hasNoDateReceipts", number(r, "noDateReceiptQty").signum() > 0);
        source.putObject("directions");
        var scenarios = source.putArray("scenarios");
        for (String type : List.of("P80", "P100", "P120")) {
            for (JsonNode s : r.path("scenarios"))
                if (type.equals(s.path("type").asText()) && !s.path("isDuplicate").asBoolean()) scenarios.add(type);
        }
        source.putArray("placeholders").add("method");
        var p = prepareStored(r, asOf);
        var names = p.values().keySet().stream().filter(k -> !Set.of("formula_desc", "risk_basis").contains(k)
                && !k.startsWith("label_") && !k.startsWith("action_") && !k.startsWith("dir_")).sorted().toList();
        ((tools.jackson.databind.node.ObjectNode) p.input()).set("placeholders", mapper.valueToTree(names));
        return p;
    }
    private Prepared prepare(JsonNode r, String asOf, boolean verifyDirections) {
        require("OK".equals(r.path("status").asText()));
        JsonNode source = r.path("insightInput");
        require(source.isObject() && "P100".equals(source.path("recommended").asText()));
        String risk = r.path("riskStatus").asText();
        require(Set.of("OK", "LOW_GRADE", "INSUFFICIENT_SAMPLE").contains(risk));
        require(risk.equals(source.path("riskStatus").asText()));
        require(source.path("methodFallback").isBoolean() && source.path("startStockEstimated").isBoolean()
                && source.path("hasNoDateReceipts").isBoolean());
        require(r.path("methodFallback").isBoolean() && r.path("startStockEstimated").isBoolean());
        require(source.path("methodFallback").asBoolean() == r.path("methodFallback").asBoolean()
                && source.path("startStockEstimated").asBoolean() == r.path("startStockEstimated").asBoolean()
                && source.path("hasNoDateReceipts").asBoolean() == (number(r, "noDateReceiptQty").signum() > 0));
        Map<String, String> v = new LinkedHashMap<>();
        String method = r.path("method").asText();
        require(Set.of("A", "B").contains(method));
        v.put("method", "계산식 " + method);
        v.put("formula_desc", "A".equals(method) ? "작년 같은 시기 판매량에 올해 최근 흐름을 반영하는 방식" : "최근 유효 관측일 평균 판매량을 쓰는 방식");
        put(v, "horizon_days", integer(number(r, "horizonDays")));
        put(v, "start_stock", stock(number(r, "startStockQty")));
        put(v, "planned_receipts", integer(number(r, "plannedReceiptsInHorizon")));
        put(v, "forecast_outbound", demand(number(r, "forecastOutboundQty")));
        put(v, "multiplier", percent(number(r, "multiplier")));
        if (r.path("methodFallback").asBoolean()) {
            String reason = r.path("methodReason").asText().strip().replaceAll("\\.+$", "");
            require(!reason.isBlank() && reason.length() <= 255 && !reason.contains("{") && !reason.contains("}"));
            put(v, "method_reason", reason);
        }
        if (r.path("startStockEstimated").asBoolean()) put(v, "as_of_date", date(asOf));
        if (r.path("noDateReceiptQty").asLong() > 0) put(v, "no_date_receipts", integer(number(r, "noDateReceiptQty")));
        if ("OK".equals(risk)) {
            String basis = r.path("riskBasis").asText();
            require(Set.of("SKU", "POOLED").contains(basis));
            put(v, "risk_basis", "SKU".equals(basis) ? "이 상품의 과거 오차 기준" : "전체 상품의 과거 오차 기준");
            put(v, "risk_pre", probability(number(r, "preArrivalRisk")));
            require(number(r, "riskSampleSize").signum() > 0);
            put(v, "risk_sample_size", integer(number(r, "riskSampleSize")));
        } else if ("LOW_GRADE".equals(risk)
                && (!r.path("forecastRangeLow").isNull() || !r.path("forecastRangeHigh").isNull())) {
            require(number(r, "forecastRangeLow").compareTo(number(r, "forecastRangeHigh")) <= 0);
            put(v, "forecast_range_low", demand(number(r, "forecastRangeLow")));
            put(v, "forecast_range_high", demand(number(r, "forecastRangeHigh")));
        }
        Map<String, JsonNode> scenarios = new HashMap<>();
        require(r.path("scenarios").isArray());
        for (JsonNode s : r.path("scenarios")) {
            String type = s.path("type").asText();
            require(Set.of("P80", "P100", "P120").contains(type) && !scenarios.containsKey(type));
            scenarios.put(type, s);
        }
        require(scenarios.keySet().equals(Set.of("P80", "P100", "P120")));
        JsonNode base = scenarios.get("P100");
        require(!base.path("isDuplicate").asBoolean() && number(base, "qty").signum() > 0
                && number(base, "qty").compareTo(number(r, "recommendedQty")) == 0);
        Map<String, String> directions = new LinkedHashMap<>();
        List<String> described = new ArrayList<>();
        for (String type : List.of("P80", "P100", "P120")) {
            JsonNode s = scenarios.get(type);
            require(s.path("isDuplicate").isBoolean());
            if (s.path("isDuplicate").asBoolean()) continue;
            described.add(type);
            String x = type.toLowerCase(Locale.ROOT);
            put(v, "label_" + x, switch(type) { case "P80" -> "권장량의 80%"; case "P120" -> "권장량의 120%"; default -> "권장량"; });
            put(v, "qty_" + x, integer(number(s, "qty")));
            put(v, "ending_" + x, stock(number(s, "expectedEndingStock")));
            put(v, "unmet_" + x, demand(number(s, "unmetDemandQty")));
            put(v, "depletion_" + x, s.hasNonNull("expectedDepletionOn") ? date(s.get("expectedDepletionOn").asText()) : "커버 기간 안에 소진되지 않음");
            if ("OK".equals(risk)) put(v, "risk_post_" + x, probability(number(s, "postArrivalRisk")));
            if ("P100".equals(type)) continue;
            int action = number(s, "qty").compareTo(number(base, "qty"));
            put(v, "action_" + x, action < 0 ? "줄이면" : action > 0 ? "늘리면" : "그대로 두면");
            put(v, "diff_" + x, integer(number(s, "qty").subtract(number(base, "qty")).abs()));
            int ending = DisplayQuantities.stock(number(s, "expectedEndingStock")).compareTo(DisplayQuantities.stock(number(base, "expectedEndingStock")));
            put(v, "dir_ending_" + x + "_connect", ending < 0 ? "줄지만" : ending > 0 ? "늘지만" : "같고");
            put(v, "dir_ending_" + x + "_end", ending < 0 ? "줄어듭니다" : ending > 0 ? "늘어납니다" : "같습니다");
            directions.put("ending_" + x + "_vs_p100", direction(ending));
            if ("OK".equals(risk)) {
                int compare = roundedPercent(number(s, "postArrivalRisk")).compareTo(roundedPercent(number(base, "postArrivalRisk")));
                put(v, "dir_risk_post_" + x + "_connect", compare < 0 ? "낮아지고" : compare > 0 ? "높아지고" : "같고");
                put(v, "dir_risk_post_" + x + "_end", compare < 0 ? "낮아집니다" : compare > 0 ? "높아집니다" : "같습니다");
                directions.put("risk_post_" + x + "_vs_p100", direction(compare));
            }
        }
        if (verifyDirections) require(source.path("directions").equals(mapper.valueToTree(directions)));
        require(source.path("scenarios").equals(mapper.valueToTree(described)));
        require(source.path("placeholders").isArray() && source.path("placeholders").size() > 0);
        Set<String> names = new java.util.HashSet<>();
        for (JsonNode key : source.path("placeholders")) require(key.isString() && v.containsKey(key.asText()) && names.add(key.asText()));
        var input = mapper.createObjectNode().put("skuLabel", "이 상품");
        for (String key : INPUT_FIELDS) input.set(key, source.get(key));
        input.set("directions", mapper.valueToTree(directions));
        return new Prepared(input, Map.copyOf(v), fallback(r, v, described, risk));
    }

    public String render(String draft, Map<String, String> values) {
        require(draft != null && !draft.isBlank() && draft.length() <= 12000);
        // All numbers must come from trusted calculated facts, even when upstream claims PASSED.
        String outside = KEY.matcher(draft).replaceAll("");
        require(!Pattern.compile("[0-9{}]").matcher(outside).find());
        var matcher = KEY.matcher(draft);
        StringBuilder out = new StringBuilder();
        boolean used = false;
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            require(value != null);
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
            used = true;
        }
        matcher.appendTail(out);
        require(used && out.indexOf("{{") < 0 && out.indexOf("}}") < 0);
        return out.toString();
    }

    private String fallback(JsonNode r, Map<String, String> v, List<String> types, String risk) {
        List<String> lines = new ArrayList<>();
        lines.add("{{method}}({{formula_desc}})로 커버 기간 {{horizon_days}}일 동안 {{forecast_outbound}}개가 팔릴 것으로 예측하고 여유 배수 {{multiplier}}를 적용한 뒤, 현재 재고 {{start_stock}}개와 도착 예정 물량 {{planned_receipts}}개를 뺀 권장 입고량은 {{qty_p100}}개입니다.");
        if (r.path("methodFallback").asBoolean()) lines.add("이 산식을 쓴 이유: {{method_reason}}.");
        if (r.path("noDateReceiptQty").asLong() > 0) lines.add("도착 예정일이 없는 기존 발주 물량 {{no_date_receipts}}개는 계산에 넣지 않았습니다.");
        if (r.path("startStockEstimated").asBoolean()) lines.add("현재 재고 {{start_stock}}개는 기준일 {{as_of_date}} 이후의 출고를 예측해 추정한 값입니다.");
        lines.add("OK".equals(risk) ? "{{qty_p100}}개를 넣으면 다음 입고 때 {{ending_p100}}개가 남고 도착 후 품절 위험은 {{risk_basis}} 추정치로 {{risk_post_p100}}입니다(표본 {{risk_sample_size}}건)." : "{{qty_p100}}개를 넣으면 다음 입고 때 {{ending_p100}}개가 남습니다.");
        if ("INSUFFICIENT_SAMPLE".equals(risk)) lines.add("품절 위험은 과거 예측 표본이 부족해 산출하지 않았습니다.");
        if ("LOW_GRADE".equals(risk)) lines.add(v.containsKey("forecast_range_low")
                ? "예측 기록이 짧아 품절 위험은 산출하지 않았고, 대신 예상 출고량 범위를 보여 드립니다: {{forecast_range_low}}~{{forecast_range_high}}개."
                : "예측 기록이 짧아 품절 위험은 산출하지 않았고, 예상 출고량 범위도 산출하지 않았습니다.");
        for (String type : List.of("P80", "P120")) {
            if (!types.contains(type)) continue;
            String template = "OK".equals(risk) ? "{{qty_X}}개로 {{action_X}} 다음 입고 때 남는 재고는 {{ending_X}}개로 {{dir_ending_X_connect}} 품절 위험은 {{risk_post_X}}로 {{dir_risk_post_X_end}}." : "{{qty_X}}개로 {{action_X}} 다음 입고 때 남는 재고는 {{ending_X}}개로 {{dir_ending_X_end}}.";
            lines.add(template.replace("_X", "_" + type.toLowerCase(Locale.ROOT)));
        }
        if ("OK".equals(risk)) lines.add("새 물량이 도착하기 전까지의 품절 위험은 수량과 관계없이 {{risk_pre}}입니다.");
        return String.join("\n", lines.stream().map(line -> KEY.matcher(line).find() ? render(line, v) : line).toList());
    }
    private static String direction(int n) { return n < 0 ? "낮음" : n > 0 ? "높음" : "같음"; }
    private static void put(Map<String, String> v, String key, String value) { v.put(key, value); }
    private static BigDecimal number(JsonNode n, String key) {
        require(n.path(key).isNumber());
        BigDecimal v = n.get(key).decimalValue();
        require(v.signum() >= 0 && v.compareTo(new BigDecimal("999999999999999")) <= 0);
        return v;
    }
    private static String integer(BigDecimal n) { return String.format(Locale.US, "%,d", n.longValueExact()); }
    private static String stock(BigDecimal n) { return String.format(Locale.US, "%,d", DisplayQuantities.stock(n)); }
    private static String demand(BigDecimal n) { return String.format(Locale.US, "%,d", DisplayQuantities.demand(n)); }
    private static BigDecimal roundedPercent(BigDecimal n) { return n.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP); }
    private static String percent(BigDecimal n) { return integer(roundedPercent(n)) + "%"; }
    private static String probability(BigDecimal n) { require(n.compareTo(BigDecimal.ONE) <= 0); return percent(n); }
    private static String date(String text) { LocalDate d = LocalDate.parse(text); return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일"; }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Insight facts mismatch"); }
}
