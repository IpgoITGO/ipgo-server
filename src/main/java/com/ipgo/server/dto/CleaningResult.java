package com.ipgo.server.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

// ipgo-ai schemas/engine.py의 CleanResponse. 전송 방식과 무관한 정제 결과 저장 경계.
public record CleaningResult(
        @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd, @NotNull LocalDate asOfDate,
        @NotNull List<@NotNull @Valid CleanProduct> products,
        @NotNull List<@NotNull @Valid Unit> inventoryUnits,
        @NotNull List<@NotNull @Valid UnitDay> unitDailyRecords,
        @NotNull List<@NotNull @Valid ProductDay> productDailyStocks,
        @NotNull @Valid Validation validation, @Positive Integer estimatedReviewCycleDays) {

    public record CleanProduct(@NotBlank @Size(max=100) String skuBarcode,
            @NotBlank @Size(max=200) String skuName, @PositiveOrZero Long unitCost, boolean isNew) { }
    public record Unit(@NotBlank @Size(max=100) String sourceInventoryKey,
            @NotBlank @Size(max=100) String skuBarcode, @NotNull LocalDate receivedOn) { }
    public record UnitDay(@NotNull @Positive Long uploadFileId, @NotBlank String sourceInventoryKey,
            @NotNull LocalDate receivedOn, @NotNull LocalDate stockDate,
            @NotNull @PositiveOrZero Long openingQty, @NotNull @PositiveOrZero Long inboundQty,
            @NotNull @PositiveOrZero Long outboundQty, @NotNull Long adjustmentQty,
            @NotNull @PositiveOrZero Long closingQty, @NotBlank @Size(max=255) String sourceSheet,
            @NotNull @Positive Integer sourceRow) { }
    public record ProductDay(@NotNull @Positive Long uploadFileId, @NotBlank String skuBarcode,
            @NotNull LocalDate stockDate, @NotNull @PositiveOrZero Long openingQty,
            @NotNull @PositiveOrZero Long inboundQty, @NotNull @PositiveOrZero Long outboundQty,
            @NotNull Long adjustmentQty, @NotNull @PositiveOrZero Long closingQty,
            @NotNull Boolean isValidObservation, @Size(max=255) String observationNote) { }
    public record Validation(@NotNull @PositiveOrZero Integer checkedSkuCount,
            @NotNull @PositiveOrZero Integer targetSkuCount,
            @NotNull List<@NotNull @Valid ExcludedSku> excludedSkus) { }
    public record ExcludedSku(@NotBlank String skuBarcode, @NotBlank @Size(max=200) String skuName,
            @NotNull LocalDate date, @NotBlank @Size(max=1000) String reason) { }
}
