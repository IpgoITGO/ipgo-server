package com.ipgo.server.product;

// 상품 목록·수정 응답 한 줄. 원가는 원 단위 정수로 돌려준다.
public record ProductItem(Long productId, String skuBarcode, String skuName, Long unitCost, boolean isActive) {

    static ProductItem from(Product product) {
        return new ProductItem(product.getId(), product.getSkuBarcode(), product.getSkuName(),
                product.getUnitCost() == null ? null : product.getUnitCost().longValue(), product.isActive());
    }
}
