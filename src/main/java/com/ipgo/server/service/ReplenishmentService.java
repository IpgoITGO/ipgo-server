package com.ipgo.server.service;

import com.ipgo.server.dto.request.DefaultSettingsRequest;
import com.ipgo.server.dto.request.ProductSettingsRequest;
import com.ipgo.server.dto.response.DefaultSettings;
import com.ipgo.server.dto.response.ProductSettings;
import com.ipgo.server.dto.response.SettingsSaved;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.ReplenishmentSetting;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.ReplenishmentSettingRepository;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 입고 조건 기본값(S1 · S2)과 상품별 입고 조건(S3). 저장 뒤 계산 시작은 계산 엔진 연동 때 붙인다.
@Service
public class ReplenishmentService {
    // 리드타임 + 입고 주기의 최댓값. AI 계산 엔진이 받는 상품별 계산 기간 한도와 같은 값이다
    // (ipgo-ai app/execution.py validate_calculation_capacity). 넘는 상품이 하나라도 있으면 계산 전체가 실패한다(#257).
    static final int MAX_CALCULATION_HORIZON_DAYS = 366;
    @org.springframework.beans.factory.annotation.Value("${ipgo.engine.default-method:B}")
    private String defaultMethod = "B";
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
        if (warehouse.isEmpty()) return DefaultSettings.from(warehouse, suggestions.defaults(warehouse), null, null);
        // 최근 12개월 입고 기록으로 추정한 주기(#166). 확인한 뒤에도 추천값으로 되돌릴 수 있게 늘 같이 준다(#214).
        var estimate = suggestions.estimate(warehouse.get());
        var suggestion = estimate.reviewCycleDays() == null ? null : new DefaultSettings.ReviewCycleSuggestion(
                estimate.reviewCycleDays(), estimate.from(), estimate.to(), estimate.productCount());
        if (warehouse.get().hasConfirmedDefaults()) {
            return DefaultSettings.from(warehouse, suggestions.defaults(warehouse), null, suggestion);
        }
        // 확인 전에는 추정한 주기를 기본값 칸에 채우고 그 근거를 같이 보여 준다(#166).
        var stored = com.ipgo.server.dto.response.ProductSettings.defaults(warehouse);
        var values = new com.ipgo.server.dto.response.ProductSettings(stored.leadTimeDays(), estimate.reviewCycleDays(),
                stored.orderUnitQty());
        var basis = suggestion == null ? null
                : new DefaultSettings.ReviewCycleBasis(estimate.from(), estimate.to(), estimate.productCount());
        return DefaultSettings.from(warehouse, values, basis, suggestion);
    }

    @Transactional
    public SettingsSaved saveDefaults(long userId, DefaultSettingsRequest request) {
        if ("LGBM19".equals(defaultMethod)) {
            if (!Integer.valueOf(5).equals(request.leadTimeDays()))
                throw ProductException.unsupportedModelPeriod("leadTimeDays");
            if (!Integer.valueOf(14).equals(request.reviewCycleDays()))
                throw ProductException.unsupportedModelPeriod("reviewCycleDays");
        }
        var next = new ProductSettings(request.leadTimeDays(), request.reviewCycleDays(), request.orderUnitQty());
        if (exceedsHorizon(next)) throw ProductException.calculationHorizonTooLong(MAX_CALCULATION_HORIZON_DAYS, null);
        var warehouse = warehouses.lockDefault(userId);
        rejectProductsOverHorizon(userId, next);
        warehouse.confirmDefaults(request.leadTimeDays(), request.reviewCycleDays(), request.orderUnitQty());
        return new SettingsSaved(true, calculations.request(userId, warehouse.getId(), "SETTINGS", Boolean.TRUE.equals(request.recalculate())));
    }

    @Transactional
    public SettingsSaved saveForProduct(long userId, long productId, ProductSettingsRequest request) {
        var warehouse = warehouses.lockDefault(userId);
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();

        // 비운 칸은 기본값(확인 전이면 제안값)을 따르므로, 실제로 적용될 값으로 합계를 본다.
        var candidate = new ReplenishmentSetting(productId);
        candidate.replace(request.leadTimeDays(), request.reviewCycleDays(), request.orderUnitQty());
        if (exceedsHorizon(ProductSettings.defaults(Optional.of(warehouse)).applying(candidate)))
            throw ProductException.calculationHorizonTooLong(MAX_CALCULATION_HORIZON_DAYS, null);

        ReplenishmentSetting setting = settings.findById(productId).orElseGet(() -> new ReplenishmentSetting(productId));
        setting.replace(request.leadTimeDays(), request.reviewCycleDays(), request.orderUnitQty());
        settings.save(setting);
        product.update(null, request.unitCost(), null);
        return new SettingsSaved(true, calculations.request(userId, warehouse.getId(), "SETTINGS", false));
    }

    // 기본값을 바꾸면 상품별 값과 합친 실제 적용 값도 바뀐다. 사용을 끈 상품도 다시 켜질 수 있어 함께 본다.
    private void rejectProductsOverHorizon(long userId, ProductSettings nextDefaults) {
        var owned = products.findByUserId(userId).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        if (owned.isEmpty()) return;
        var over = settings.findByProductIdIn(owned.keySet()).stream()
                .filter(override -> exceedsHorizon(nextDefaults.applying(override)))
                .map(override -> owned.get(override.getProductId()))
                .sorted(Comparator.comparing(Product::getId)).toList();
        if (over.isEmpty()) return;
        String names = "'" + over.getFirst().getSkuName() + "'" + (over.size() > 1 ? " 외 " + (over.size() - 1) + "개" : "");
        throw ProductException.calculationHorizonTooLong(MAX_CALCULATION_HORIZON_DAYS,
                names + " 상품의 상품별 입고 조건과 합치면 " + MAX_CALCULATION_HORIZON_DAYS + "일을 넘어요.");
    }

    // 아직 정해지지 않은 칸(확인 전 입고 주기)은 가장 작은 값인 1일로 본다. 그래도 넘으면 어떤 값이 와도 넘는다.
    static boolean exceedsHorizon(ProductSettings applied) {
        long leadTime = applied.leadTimeDays() == null ? 1 : applied.leadTimeDays();
        long reviewCycle = applied.reviewCycleDays() == null ? 1 : applied.reviewCycleDays();
        return leadTime + reviewCycle > MAX_CALCULATION_HORIZON_DAYS;
    }
}
