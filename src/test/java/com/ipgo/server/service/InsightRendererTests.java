package com.ipgo.server.service;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class InsightRendererTests {
    final JsonMapper mapper = JsonMapper.builder().build();
    final InsightRenderer renderer = new InsightRenderer(mapper);
    ObjectNode r;
    @BeforeEach void fixture() throws Exception {
        r=(ObjectNode)mapper.readTree(getClass().getResourceAsStream("/insight/result.json"));
    }
    ObjectNode input() { return (ObjectNode)r.path("insightInput"); }
    void risk(String status) {
        r.put("riskStatus",status); input().put("riskStatus",status);
        var names=input().putArray("placeholders");
        for(String k:List.of("method","qty_p80","qty_p100","qty_p120","ending_p80","ending_p100","ending_p120")) names.add(k);
        input().putObject("directions").put("ending_p80_vs_p100","낮음").put("ending_p120_vs_p100","높음");
    }
    @Test void displayFormatsMatchComparisonTableAndRoundedDirections() {
        var p=renderer.prepare(r,"2026-09-01");
        assertThat(p.values()).containsEntry("start_stock","10").containsEntry("forecast_outbound","96")
                .containsEntry("ending_p100","40").containsEntry("multiplier","105%")
                .containsEntry("dir_risk_post_p80_end","같습니다");
        assertThat(p.fallback()).contains("40개", "같습니다").doesNotContain("{{");
    }
    @Test void lowGradeSuppressesAllRiskEvenIfStaleValuesRemain() {
        risk("LOW_GRADE"); r.put("forecastRangeLow",70.1).put("forecastRangeHigh",125.1);
        var p=renderer.prepare(r,"2026-09-01");
        assertThat(p.values()).doesNotContainKeys("risk_pre","risk_sample_size","risk_post_p100","risk_basis");
        assertThat(p.fallback()).contains("71~126개").doesNotContain("추정치로");
    }
    @Test void shortHistoryLowGradeAllowsAbsentRangeWithoutInventingFacts() {
        risk("LOW_GRADE"); r.put("validObservationDays",12).put("accuracyGrade","LOW");
        r.putNull("forecastRangeLow").putNull("forecastRangeHigh");
        var p=renderer.prepare(r,"2026-11-13");
        assertThat(p.values()).doesNotContainKeys("forecast_range_low","forecast_range_high","risk_pre","risk_post_p100");
        assertThat(p.input().path("riskStatus").asText()).isEqualTo("LOW_GRADE");
        assertThat(p.fallback()).contains("품절 위험","범위도 산출하지 않았습니다").doesNotContain("~");
        assertThat(renderer.prepareHistorical(r,"2026-11-13").input().toString()).doesNotContain("forecast_range", "risk_pre");
    }
    @Test void malformedLowGradeRangesStillFailClosed() {
        risk("LOW_GRADE");
        for (double[] range : List.of(new double[]{-1,20}, new double[]{30,20})) {
            r.put("forecastRangeLow",range[0]).put("forecastRangeHigh",range[1]);
            assertThatThrownBy(()->renderer.prepare(r,"2026-11-13")).isInstanceOf(IllegalArgumentException.class);
        }
        r.putNull("forecastRangeLow").put("forecastRangeHigh",20);
        assertThatThrownBy(()->renderer.prepare(r,"2026-11-13")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void insufficientSampleDoesNotInventPercentageOrBasis() {
        risk("INSUFFICIENT_SAMPLE"); var p=renderer.prepare(r,"2026-09-01");
        assertThat(p.fallback()).contains("표본이 부족해 산출하지 않았습니다");
        assertThat(p.values()).doesNotContainKeys("risk_pre","risk_post_p100","risk_basis");
    }
    @Test void conditionalClausesUseOnlyRealFacts() {
        r.put("methodFallback",true).put("methodReason","작년 자료가 없어요.").put("startStockEstimated",true).put("noDateReceiptQty",7);
        input().put("methodFallback",true).put("startStockEstimated",true).put("hasNoDateReceipts",true);
        var text=renderer.prepare(r,"2026-09-01").fallback();
        assertThat(text).contains("작년 자료가 없어요.","물량 7개", "기준일 9월 1일");
        assertThatThrownBy(()->renderer.prepare(r,null)).isInstanceOf(RuntimeException.class);
    }
    @Test void duplicateScenarioIsOmittedAndCannotSupplyAPlaceholder() {
        ((ObjectNode)r.path("scenarios").get(0)).put("isDuplicate",true);
        input().putArray("scenarios").add("P100").add("P120");
        input().putArray("placeholders").add("method").add("qty_p100").add("ending_p100");
        input().putObject("directions").put("ending_p120_vs_p100","높음").put("risk_post_p120_vs_p100","낮음");
        var p=renderer.prepare(r,"2026-09-01");
        assertThat(p.values()).doesNotContainKey("qty_p80");
        assertThat(p.fallback()).doesNotContain("80개");
    }
    @Test void inconsistentComparisonIsRejectedBeforeAnyCall() {
        ((ObjectNode)input().path("directions")).put("risk_post_p80_vs_p100","높음");
        assertThatThrownBy(()->renderer.prepare(r,"2026-09-01")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unavailableNumericFactCannotBecomeZero() {
        r.putNull("startStockQty");
        assertThatThrownBy(()->renderer.prepare(r,"2026-09-01")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedBraceAndUntrustedRawNumberAreRejected() {
        var p=renderer.prepare(r,"2026-09-01");
        assertThatThrownBy(()->renderer.render("{{qty_p100}}개 {임의} 100개",p.values())).isInstanceOf(IllegalArgumentException.class);
    }
}
