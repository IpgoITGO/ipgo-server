package com.ipgo.server.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// #168 유통기한 임박 기준(일). 1~365일.
public record ExpirySettingsRequest(@NotNull @Min(1) @Max(365) Integer imminentDays) {
}
