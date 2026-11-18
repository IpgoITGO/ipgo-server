package com.ipgo.server.service;

import com.ipgo.server.dto.request.DefaultSettingsRequest;
import com.ipgo.server.dto.response.ProductSettings;
import java.util.Optional;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.ReplenishmentSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ModelPeriodSettingsTests {
    final WarehouseService warehouses = mock(WarehouseService.class);
    final ProductRepository products = mock(ProductRepository.class);
    final ReplenishmentSettingRepository settings = mock(ReplenishmentSettingRepository.class);
    final EngineCalculationService calculations = mock(EngineCalculationService.class);
    final ReviewCycleSuggestionService suggestions = mock(ReviewCycleSuggestionService.class);
    final ReplenishmentService service = new ReplenishmentService(warehouses, products, settings,
            calculations, suggestions);

    @Test
    void supportIsReportedForModelWithoutCreatingWarehouseOrChangingSuggestedValues() {
        ReflectionTestUtils.setField(service, "defaultMethod", "LGBM19");
        when(warehouses.findDefault(43)).thenReturn(Optional.empty());
        when(suggestions.defaults(Optional.empty())).thenReturn(new ProductSettings(5, null, 1L));
        var response = service.defaults(43);
        assertThat(response.confirmed()).isFalse();
        assertThat(response.leadTimeDays()).isEqualTo(5);
        assertThat(response.reviewCycleDays()).isNull();
        assertThat(response.calculationSupport().method()).isEqualTo("LGBM19");
        assertThat(response.calculationSupport().leadTimeDays()).isEqualTo(5);
        assertThat(response.calculationSupport().reviewCycleDays()).isEqualTo(14);
        verify(warehouses, never()).lockDefault(anyLong());
        verifyNoInteractions(calculations);
    }

    @Test
    void supportIsReportedBeforeWarehouseSettingsAreConfirmed() {
        ReflectionTestUtils.setField(service, "defaultMethod", "LGBM19");
        var warehouse = mock(Warehouse.class);
        when(warehouses.findDefault(43)).thenReturn(Optional.of(warehouse));
        when(warehouse.hasConfirmedDefaults()).thenReturn(false);
        when(suggestions.estimate(warehouse)).thenReturn(
                new ReviewCycleSuggestionService.Estimate(21, null, null, 3));
        var response = service.defaults(43);
        assertThat(response.confirmed()).isFalse();
        assertThat(response.reviewCycleDays()).isEqualTo(21);
        assertThat(response.calculationSupport().reviewCycleDays()).isEqualTo(14);
        verifyNoInteractions(calculations);
    }

    @Test
    void supportIsIndependentOfConfirmedWarehouseAndOrdinaryMethodsHaveNoRestriction() {
        var warehouse = mock(Warehouse.class);
        when(warehouses.findDefault(43)).thenReturn(Optional.of(warehouse));
        when(warehouse.hasConfirmedDefaults()).thenReturn(true);
        when(suggestions.estimate(warehouse)).thenReturn(new ReviewCycleSuggestionService.Estimate(null, null, null, 0));
        when(suggestions.defaults(Optional.of(warehouse))).thenReturn(new ProductSettings(5, 14, 3L));
        ReflectionTestUtils.setField(service, "defaultMethod", "LGBM19");
        var model = service.defaults(43);
        assertThat(model.confirmed()).isTrue();
        assertThat(model.orderUnitQty()).isEqualTo(3L);
        assertThat(model.calculationSupport().reviewCycleDays()).isEqualTo(14);
        for (String method : new String[] {"A", "B"}) {
            ReflectionTestUtils.setField(service, "defaultMethod", method);
            var ordinary = service.defaults(43);
            assertThat(ordinary.confirmed()).isTrue();
            assertThat(ordinary.calculationSupport()).isNull();
            assertThat(ordinary.orderUnitQty()).isEqualTo(3L);
        }
        verifyNoInteractions(calculations);
    }

    @Test
    void rejectsUnsupportedLeadBeforeChangingAnyState() {
        ReflectionTestUtils.setField(service, "defaultMethod", "LGBM19");
        var failure = assertThrows(ProductException.class,
                () -> service.saveDefaults(43, new DefaultSettingsRequest(13, 53, 1L, true)));
        assertThat(failure.code()).isEqualTo("MODEL_PERIOD_UNSUPPORTED");
        assertThat(failure.status().value()).isEqualTo(400);
        assertThat(failure.field()).isEqualTo("leadTimeDays");
        verifyNoInteractions(warehouses, products, settings, calculations);
    }

    @Test
    void rejectsUnsupportedReviewBeforeChangingAnyState() {
        ReflectionTestUtils.setField(service, "defaultMethod", "LGBM19");
        var failure = assertThrows(ProductException.class,
                () -> service.saveDefaults(43, new DefaultSettingsRequest(5, 53, 3L, true)));
        assertThat(failure.field()).isEqualTo("reviewCycleDays");
        verifyNoInteractions(warehouses, products, settings, calculations);
    }

    @Test
    void supportedModelPeriodsPreserveOrderUnitAndRequestOneCalculation() {
        ReflectionTestUtils.setField(service, "defaultMethod", "LGBM19");
        var warehouse = mock(Warehouse.class);
        when(warehouse.getId()).thenReturn(33L);
        when(warehouses.lockDefault(43)).thenReturn(warehouse);
        when(calculations.request(43, 33, "SETTINGS", true)).thenReturn(448L);
        var response = service.saveDefaults(43, new DefaultSettingsRequest(5, 14, 3L, true));
        verify(warehouse).confirmDefaults(5, 14, 3L);
        verify(calculations, times(1)).request(43, 33, "SETTINGS", true);
        assertThat(response.runId()).isEqualTo(448L);
    }

    @Test
    void legacySettingsRemainConfigurable() {
        var warehouse = mock(Warehouse.class);
        when(warehouse.getId()).thenReturn(33L);
        when(warehouses.lockDefault(43)).thenReturn(warehouse);
        service.saveDefaults(43, new DefaultSettingsRequest(13, 53, 1L, false));
        verify(warehouse).confirmDefaults(13, 53, 1L);
        verify(calculations).request(43, 33, "SETTINGS", false);
    }
}

