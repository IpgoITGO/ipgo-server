package com.ipgo.server.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional(readOnly = true)
    public ProductPage list(long userId, String query, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw ProductException.invalidInput();
        String keyword = query == null || query.isBlank() ? null : query.strip();
        Page<Product> result = products.search(userId, keyword, PageRequest.of(page, size, Sort.by("id")));
        return new ProductPage(result.map(ProductItem::from).getContent(), result.getTotalElements(), page, size);
    }

    @Transactional
    public ProductItem update(long userId, long productId, ProductUpdateRequest request) {
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();
        product.update(request.skuName(), request.unitCost(), request.isActive());
        return ProductItem.from(product);
    }
}
