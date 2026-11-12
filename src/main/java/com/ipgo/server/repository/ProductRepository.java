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

    List<Product> findByUserId(Long userId);
    long countByUserIdAndActivationModeAndActiveFalse(Long userId, String activationMode);

    // 검색어가 상품명이나 바코드 어디에 있어도 찾는다. 검색어가 빈 문자열이면 전체.
    // hidden은 덮어쓴 파일에만 있던 상품(#188, CurrentProducts)이다. 빈 목록 대신 존재하지 않는 id(-1)를 넣어 부른다.
    @Query("""
            SELECT p FROM Product p
            WHERE p.userId = :userId AND p.id NOT IN :hidden
              AND (:includeAutoInactive = TRUE OR p.activationMode <> 'AUTO' OR p.active = TRUE)
              AND (LOWER(p.skuName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR p.skuBarcode LIKE CONCAT('%', :query, '%'))
            """)
    Page<Product> search(@Param("userId") Long userId, @Param("hidden") Collection<Long> hidden, @Param("query") String query, @Param("includeAutoInactive") boolean includeAutoInactive,
            Pageable pageable);
}
