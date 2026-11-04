package com.ipgo.server.service;

import com.ipgo.server.dto.response.ScenarioComparison;
import com.ipgo.server.entity.CalculationResult;
import com.ipgo.server.entity.Product;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.CalculationResultRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.util.DisplayQuantities;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// K2 시나리오 비교표 (BE-08). 계산 엔진이 scenario_results에 저장한 값을 읽기만 한다.
@Service
public class ScenarioService {
    private final CalculationResultRepository results;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;

    public ScenarioService(CalculationResultRepository results, ProductRepository products, JdbcTemplate jdbc) {
        this.results = results;
        this.products = products;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ScenarioComparison scenarios(long userId, long resultId) {
        CalculationResult result = results.findById(resultId).orElseThrow(ProductException::notFound);
        Product product = products.findById(result.getProductId()).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();

        BigDecimal preArrival = result.getPreArrivalUnmetQty();
        List<ScenarioComparison.Scenario> scenarios = jdbc.query("""
                SELECT id, scenario_type, scenario_qty, is_duplicate, expected_depletion_on, expected_ending_stock,
                    unmet_demand_qty, post_arrival_risk
                FROM scenario_results WHERE result_id = ?
                ORDER BY CASE scenario_type WHEN 'P80' THEN 1 WHEN 'P100' THEN 2 WHEN 'P120' THEN 3 ELSE 4 END, id
                """, (rs, i) -> new ScenarioComparison.Scenario(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getBoolean(4), rs.getObject(5, LocalDate.class), DisplayQuantities.stock(rs.getBigDecimal(6)),
                        DisplayQuantities.demand(rs.getBigDecimal(7)), postArrival(rs.getBigDecimal(7), preArrival),
                        rs.getBigDecimal(8)), resultId);
        // 계산일이 기준일보다 늦으면 시작 재고를 예상 출고로 이어서 추정했으므로 비교표 값도 추정치다.
        boolean estimated = result.getStartDate().isAfter(result.getOriginDate());
        return new ScenarioComparison(result.getId(), estimated, result.getPreArrivalRisk(),
                result.getRiskSampleSize(), result.getRiskBasis(), riskStatus(result),
                DisplayQuantities.demand(preArrival), scenarios);
    }

    // 도착 후 몫 = 합계 − 도착 전 몫. 화면용 정수로 올리기 전 소수 값으로 빼야 1개씩 어긋나지 않는다.
    private static Long postArrival(BigDecimal total, BigDecimal preArrival) {
        if (total == null || preArrival == null) return null;
        return DisplayQuantities.demand(total.subtract(preArrival).max(BigDecimal.ZERO));
    }

    // 엔진은 정확도가 낮거나 표본이 모자라면 위험값을 비워 둔다(ipgo-ai 0002 3-8).
    private static String riskStatus(CalculationResult result) {
        if (!CalculationResult.OK.equals(result.getStatus())) return null;
        if ("LOW".equals(result.getAccuracyGrade())) return "LOW_GRADE";
        return result.getPreArrivalRisk() == null ? "INSUFFICIENT_SAMPLE" : "OK";
    }
}
