package com.ipgo.server.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class AccuracyEvaluationFactsTests {
    final JsonMapper mapper = JsonMapper.builder().build();
    private tools.jackson.databind.JsonNode fixture() throws Exception {
        return mapper.readTree(getClass().getResourceAsStream("/fixtures/accuracy-history-calculation.json"));
    }
    @Test void genuineAiEvaluationAndOneSampleKeepRawFractions() throws Exception {
        var f=fixture(); var result=(ObjectNode) f.path("response").path("results").get(0);
        var evaluation=(ObjectNode) result.path("accuracyEvaluation");
        var first=evaluation.path("history").get(0).deepCopy();
        evaluation.putArray("history").add(first);
        result.put("coverTrialCount",1); result.put("coverSuccessCount",first.path("covered").asBoolean()?1:0);
        var valid=AccuracyEvaluationFacts.validate(result,f.path("request"));
        assertThat(valid.path("history").get(0).path("forecastQty").decimalValue()).isEqualByComparingTo("12.5");
    }
    @Test void noSampleDenominatorAndUnavailableAreNotFalseSuccess() throws Exception {
        var f=fixture(); var result=(ObjectNode) f.path("response").path("results").get(0);
        var e=(ObjectNode) result.path("accuracyEvaluation"); e.putArray("history");
        for(var key:java.util.List.of("coverTrialCount","coverSuccessCount","forecastErrorRate")) result.putNull(key);
        e.put("status","EMPTY"); e.put("reason","NO_VALID_SAMPLES");
        assertThat(AccuracyEvaluationFacts.validate(result,f.path("request")).path("history").isEmpty()).isTrue();
        e.put("status","UNAVAILABLE"); e.put("reason","EVALUATION_UNAVAILABLE");
        assertThat(AccuracyEvaluationFacts.validate(result,f.path("request")).path("status").asText()).isEqualTo("UNAVAILABLE");
        result.put("coverSuccessCount",1);
        assertThatThrownBy(()->AccuracyEvaluationFacts.validate(result,f.path("request"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsScalarTypeOverflowUnknownFieldsAndPolicyMismatch() throws Exception {
        for(String defect:java.util.List.of("versionOverflow","boolean","policyHash","method","observedThrough","nested","extraPoint","horizonOverflow","tooMany","trialOverflow","successOverflow")) {
            var f=fixture(); var result=(ObjectNode) f.path("response").path("results").get(0);
            var e=(ObjectNode) result.path("accuracyEvaluation");
            var first=(ObjectNode)e.path("history").get(0);
            switch(defect) {
                case "versionOverflow" -> e.put("schemaVersion",4294967297L);
                case "boolean" -> first.put("covered","true");
                case "policyHash" -> e.put("policyHash","a".repeat(64));
                case "method" -> e.put("method","A");
                case "observedThrough" -> e.put("observedThrough","2027-01-01");
                case "nested" -> e.putObject("reason").put("arbitrary","payload");
                case "extraPoint" -> first.put("arbitrary","payload");
                case "horizonOverflow" -> e.put("horizonDays",4294967306L);
                case "trialOverflow" -> result.put("coverTrialCount",4294967308L);
                case "successOverflow" -> result.put("coverSuccessCount",4294967308L);
                case "tooMany" -> { var h=(tools.jackson.databind.node.ArrayNode)e.path("history"); h.add(first.deepCopy());h.add(first.deepCopy()); }
            }
            assertThatThrownBy(()->AccuracyEvaluationFacts.validate(result,f.path("request"))).as(defect).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void unavailableMetadataStillRejectsInvalidDatesAndNegativeHorizons() throws Exception {
        for (String defect : java.util.List.of("date","horizon")) {
            var f=fixture(); var result=(ObjectNode) f.path("response").path("results").get(0);
            var e=(ObjectNode) result.path("accuracyEvaluation"); e.putArray("history");
            e.put("status","UNAVAILABLE"); e.put("reason","EVALUATION_UNAVAILABLE");
            for(var key:java.util.List.of("coverTrialCount","coverSuccessCount","forecastErrorRate")) result.putNull(key);
            if (defect.equals("date")) e.put("periodStart","invalid"); else e.put("horizonDays",-1);
            assertThatThrownBy(()->AccuracyEvaluationFacts.validate(result,f.path("request"))).as(defect).isInstanceOf(java.lang.RuntimeException.class);
        }
    }
    @Test void legacyMissingEvaluationIsAccepted() throws Exception {
        var f=fixture(); var result=(ObjectNode)f.path("response").path("results").get(0);result.remove("accuracyEvaluation");
        assertThat(AccuracyEvaluationFacts.validate(result,f.path("request"))).isNull();
    }
}
