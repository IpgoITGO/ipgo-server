package com.ipgo.server.service;

import com.ipgo.server.dto.request.DefaultSettingsRequest;
import com.ipgo.server.dto.request.ProductSettingsRequest;
import com.ipgo.server.dto.response.DefaultSettings;
import com.ipgo.server.dto.response.SettingsSaved;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.ReplenishmentSetting;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.ReplenishmentSettingRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 입고 조건 기본값(S1 · S2)과 상품별 입고 조건(S3). 저장 뒤 계산 시작은 계산 엔진 연동 때 붙인다.
@Service
public class ReplenishmentService {
    private final WarehouseService warehouses;
    private final ProductRepository products;
    private final ReplenishmentSettingRepository settings;
    private final EngineCalculationService calculations;
    private final ReviewCycleSuggestionService suggestions;

    public ReplenishmentService(WarehouseService warehouses, ProductRepository products,
            ReplenishmentSettingRepository settings, EngineCalculationService calculations, ReviewCycleSuggestionService suggestions) {
        this.warehouses = warehouses;
        this.products = products;
        this.settings = settings;
        this.calculations = calculations;
        this.suggestions = suggestions;
    }

    @Transactional(readOnly = true)
    public DefaultSettings defaults(long userId) {
        var warehouse = warehouses.findDefault(userId);
        return DefaultSettings.from(warehouse, suggestions.defaults(warehouse));
    }

    @Transactional
    public SettingsSaved saveDefaults(long userId, DefaultSettingsRequest request) {
        var warehouse = warehouses.lockDefault(userId);
        warehouse.confirmDefaults(request.leadTimeDays(), request.reviewCycleDays(), request.orderUnitQty());
        return new SettingsSaved(true, calculations.request(userId, warehouse.getId(), "SETTINGS", Boolean.TRUE.equals(request.recalculate())));
    }

    @Transactional
    public SettingsSaved saveForProduct(long userId, long productId, ProductSettingsRequest request) {
        var warehouse = warehouses.lockDefault(userId);
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();

        ReplenishmentSetting setting = settings.findById(productId).orElseGet(() -> new ReplenishmentSetting(productId));
        setting.replace(request.leadTimeDays(), request.reviewCycleDays(), request.orderUnitQty());
        settings.save(setting);
        product.update(null, request.unitCost(), null);
        return new SettingsSaved(true, calculations.request(userId, warehouse.getId(), "SETTINGS", false));
    }
}
