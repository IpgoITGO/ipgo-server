package com.ipgo.server.controller;

import com.ipgo.server.dto.request.ProductUpdateRequest;
import com.ipgo.server.dto.response.ProductDetail;
import com.ipgo.server.dto.response.ProductItem;
import com.ipgo.server.dto.response.ProductPage;
import com.ipgo.server.service.ProductDetailService;
import com.ipgo.server.service.ProductService;

import java.security.Principal;
import java.time.LocalDate;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final ProductDetailService productDetailService;

    public ProductController(ProductService productService, ProductDetailService productDetailService) {
        this.productService = productService;
        this.productDetailService = productDetailService;
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

    // K1 SKU 상세. from · to는 재고 추이 기간이다 (YYYY-MM-DD).
    @GetMapping("/{productId}/detail")
    public ProductDetail detail(Principal principal, @PathVariable long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return productDetailService.detail(userId(principal), productId, from, to);
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
