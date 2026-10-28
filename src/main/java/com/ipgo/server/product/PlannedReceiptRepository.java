package com.ipgo.server.product;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlannedReceiptRepository extends JpaRepository<PlannedReceipt, Long> {
    // 취소한 발주는 빼고, 도착 예정일이 빠른 순(없으면 맨 뒤)으로 돌려준다.
    @Query("""
            SELECT r FROM PlannedReceipt r
            WHERE r.warehouseId = :warehouseId AND r.status <> 'CANCELLED'
            ORDER BY CASE WHEN r.expectedOn IS NULL THEN 1 ELSE 0 END, r.expectedOn, r.id
            """)
    List<PlannedReceipt> findActive(@Param("warehouseId") Long warehouseId);
}
