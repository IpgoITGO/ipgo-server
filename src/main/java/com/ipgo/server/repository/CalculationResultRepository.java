package com.ipgo.server.repository;

import com.ipgo.server.entity.CalculationResult;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalculationResultRepository extends JpaRepository<CalculationResult, Long> {
    List<CalculationResult> findByRunId(Long runId);

    Optional<CalculationResult> findByRunIdAndProductId(Long runId, Long productId);
}
