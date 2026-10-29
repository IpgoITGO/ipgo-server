package com.ipgo.server.service;

import com.ipgo.server.dto.request.ProductUpdateRequest;
import com.ipgo.server.dto.response.ProductItem;
import com.ipgo.server.dto.response.ProductPage;
import com.ipgo.server.dto.response.ProductSettings;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.ReplenishmentSetting;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.ReplenishmentSettingRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository products;
    private final WarehouseService warehouses;
    private final ReplenishmentSettingRepository settings;

    public ProductService(ProductRepository products, WarehouseService warehouses,
            ReplenishmentSettingRepository settings) {
        this.products = products;
        this.warehouses = warehouses;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ProductPage list(long userId, String query, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw ProductException.invalidInput();
        // 검색어가 없으면 빈 문자열로 보내 전체를 찾는다. null을 넘기면 PostgreSQL이 값의 타입을 몰라 실패한다.
        String keyword = query == null ? "" : query.strip();
        Page<Product> result = products.search(userId, keyword, PageRequest.of(page, size, Sort.by("id")));
        return new ProductPage(items(userId, result.getContent()), result.getTotalElements(), page, size);
    }

    @Transactional
    public ProductItem update(long userId, long productId, ProductUpdateRequest request) {
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();
        product.update(request.skuName(), request.unitCost(), request.isActive());
        return items(userId, List.of(product)).getFirst();
    }

    // 창고 기본값과 상품별 입고 조건을 합쳐 실제 적용되는 값을 붙인다.
    private List<ProductItem> items(long userId, List<Product> page) {
        ProductSettings defaults = ProductSettings.defaults(warehouses.findDefault(userId));
        Map<Long, ReplenishmentSetting> overrides = settings.findByProductIdIn(page.stream().map(Product::getId).toList())
                .stream().collect(Collectors.toMap(ReplenishmentSetting::getProductId, Function.identity()));
        return page.stream().map(product -> ProductItem.from(product, defaults, overrides.get(product.getId()))).toList();
    }
}
