package com.ipgo.server.dto.response;

import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.ReplenishmentSetting;

import java.util.ArrayList;
import java.util.List;

// 상품 목록·수정 응답 한 줄. 원가는 원 단위 정수로 돌려준다.
// settings는 실제 적용되는 입고 조건이고, overridden은 기본값 대신 상품별 값을 쓰는 칸 이름이다.
public record ProductItem(Long productId, String skuBarcode, String skuName, Long unitCost, boolean isActive,
        ProductSettings settings, List<String> overridden) {

    public static ProductItem from(Product product, ProductSettings defaults, ReplenishmentSetting override) {
        return new ProductItem(product.getId(), product.getSkuBarcode(), product.getSkuName(),
                product.getUnitCost() == null ? null : product.getUnitCost().longValue(), product.isActive(),
                defaults.applying(override), overridden(override));
    }

    private static List<String> overridden(ReplenishmentSetting override) {
        List<String> fields = new ArrayList<>();
        if (override == null) return fields;
        if (override.getLeadTimeDays() != null) fields.add("leadTimeDays");
        if (override.getReviewCycleDays() != null) fields.add("reviewCycleDays");
        if (override.getOrderUnitQty() != null) fields.add("orderUnitQty");
        return fields;
    }
}
