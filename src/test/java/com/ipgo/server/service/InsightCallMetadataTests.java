package com.ipgo.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InsightCallMetadataTests {
    private final JsonMapper mapper = new JsonMapper();
    @Test void oldResponseKeepsUnknownUsageAndNullableCost() {
        var m = InsightCallMetadata.read(mapper.readTree("{\"promptVersion\":\"v1\",\"latencyMs\":3,\"costUsd\":null}"));
        assertThat(m.model()).isNull(); assertThat(m.inputTokens()).isNull();
        assertThat(m.outputTokens()).isNull(); assertThat(m.cost()).isNull();
    }
    @ParameterizedTest
    @ValueSource(strings = {"\"inputTokens\":-1", "\"inputTokens\":1.2", "\"inputTokens\":2147483648",
            "\"inputTokens\":true", "\"inputTokens\":\"3\"", "\"outputTokens\":-1",
            "\"modelName\":\" \"", "\"modelName\":12", "\"costUsd\":-0.1", "\"costUsd\":1000000",
            "\"latencyMs\":-1", "\"latencyMs\":2147483648", "\"promptVersion\":\"mock\""})
    void malformedObservationsCannotBecomeBillingEvidence(String field) {
        var r = mapper.readTree("{\"promptVersion\":\"v1\",\"latencyMs\":3,\"costUsd\":0.001,"+field+"}");
        assertThatThrownBy(() -> InsightCallMetadata.read(r)).isInstanceOf(IllegalArgumentException.class);
    }
}
