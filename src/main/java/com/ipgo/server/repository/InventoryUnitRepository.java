package com.ipgo.server.repository;

import com.ipgo.server.entity.InventoryUnit;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryUnitRepository extends JpaRepository<InventoryUnit, Long> {
    List<InventoryUnit> findByWarehouseIdAndSourceInventoryKeyIn(Long warehouseId, Collection<String> keys);
    List<InventoryUnit> findByProductId(Long productId);

    // SKU 상세의 "처음 입고한 날"
    Optional<InventoryUnit> findFirstByProductIdOrderByReceivedOnAsc(Long productId);
}
