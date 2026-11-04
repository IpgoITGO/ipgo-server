package com.ipgo.server.repository;

import com.ipgo.server.entity.CalculationRun;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalculationRunRepository extends JpaRepository<CalculationRun, Long> {
    // 가장 최근에 성공한 계산. 대시보드는 이 결과를 보여준다.
    Optional<CalculationRun> findFirstByWarehouseIdAndStatusOrderByIdDesc(Long warehouseId, String status);

    // 가장 최근 계산. 이것이 실패면 화면은 "갱신 전"이다.
    Optional<CalculationRun> findFirstByWarehouseIdOrderByIdDesc(Long warehouseId);
}
