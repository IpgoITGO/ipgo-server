package com.ipgo.server.repository;

import com.ipgo.server.entity.Product;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByUserIdAndSkuBarcodeIn(Long userId, Collection<String> skuBarcodes);

    // 검색어가 상품명이나 바코드 어디에 있어도 찾는다. 검색어가 없으면 전체.
    @Query("""
            SELECT p FROM Product p
            WHERE p.userId = :userId
              AND (:query IS NULL
                   OR LOWER(p.skuName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR p.skuBarcode LIKE CONCAT('%', :query, '%'))
            """)
    Page<Product> search(@Param("userId") Long userId, @Param("query") String query, Pageable pageable);
}
