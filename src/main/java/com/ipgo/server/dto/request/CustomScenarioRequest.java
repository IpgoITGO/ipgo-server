package com.ipgo.server.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ipgo.server.exception.ProductException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.JsonNode;

public record CustomScenarioRequest(@NotNull @Positive @Max(999_999_999_999_999L) Long qty) {
    @JsonCreator
    public static CustomScenarioRequest read(@JsonProperty("qty") JsonNode qty) {
        if (qty == null || !qty.isIntegralNumber() || !qty.canConvertToLong())
            throw ProductException.invalidInput();
        return new CustomScenarioRequest(qty.longValue());
    }
}
