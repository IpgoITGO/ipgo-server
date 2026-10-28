package com.ipgo.server.product;

import java.security.Principal;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // P1 상품 목록 · 검색
    @GetMapping
    public ProductPage list(Principal principal, @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return productService.list(userId(principal), query, page, size);
    }

    // P2 상품명 · 원가 · 사용 여부 수정
    @PatchMapping("/{productId}")
    public ProductItem update(Principal principal, @PathVariable long productId,
            @Valid @RequestBody ProductUpdateRequest request) {
        return productService.update(userId(principal), productId, request);
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
