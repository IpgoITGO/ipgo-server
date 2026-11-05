package com.ipgo.server.service;

import com.ipgo.server.exception.ProductException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reconstructs only documented flags; missing historical evidence fails before spending the attempt. */
@Component
public class StoredInsightFacts {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final InsightRenderer renderer;
    public StoredInsightFacts(JdbcTemplate jdbc, JsonMapper mapper, InsightRenderer renderer) {
        this.jdbc = jdbc; this.mapper = mapper; this.renderer = renderer;
    }

    InsightRenderer.Prepared restore(long resultId, long owner) {
        try {
            var rows = jdbc.query("""
                    SELECT r.*, cr.as_of_date, cr.status AS run_status, cr.warehouse_id
                    FROM calculation_results r JOIN calculation_runs cr ON cr.id=r.run_id WHERE r.id=?
                    """, (rs, n) -> {
                var f = mapper.createObjectNode();
                String[][] fields = {{"method","method"},{"method_reason","methodReason"},{"status","status"},
                        {"horizon_days","horizonDays"},{"start_stock_qty","startStockQty"},{"planned_receipt_qty","plannedReceiptsInHorizon"},
                        {"forecast_outbound_qty","forecastOutboundQty"},{"multiplier","multiplier"},{"recommended_qty","recommendedQty"},
                        {"pre_arrival_risk","preArrivalRisk"},{"risk_basis","riskBasis"},{"risk_sample_size","riskSampleSize"},
                        {"forecast_range_low","forecastRangeLow"},{"forecast_range_high","forecastRangeHigh"}};
                for (var field : fields) f.set(field[1], mapper.valueToTree(rs.getObject(field[0])));
                return new Historical(f, EngineJobs.readDatabaseJson(mapper, rs.getString("input_snapshot")),
                        rs.getLong("product_id"), rs.getLong("warehouse_id"), rs.getDate("origin_date").toLocalDate(),
                        rs.getDate("start_date").toLocalDate(), rs.getDate("as_of_date").toLocalDate(),
                        (Integer) rs.getObject("valid_observation_days"), rs.getString("run_status"));
            }, resultId);
            require(rows.size() == 1);
            var h = rows.getFirst(); var f = h.facts(); var input = h.input();
            require("OK".equals(f.path("status").asText()) && "DONE".equals(h.runStatus()));
            require(input.isObject() && input.path("userId").isIntegralNumber() && input.path("userId").asLong() == owner);
            require(input.path("warehouseId").isIntegralNumber() && input.path("warehouseId").asLong() == h.warehouse());
            require(input.path("productIds").isArray() && input.path("productIds").size() == 1
                    && input.path("productIds").get(0).isIntegralNumber() && input.path("productIds").get(0).asLong() == h.product());
            require(h.origin().equals(h.asOf()) && h.origin().equals(LocalDate.parse(input.path("asOfDate").asText()))
                    && h.start().equals(LocalDate.parse(input.path("today").asText())) && !h.start().isBefore(h.origin()));
            String preferred = input.path("policy").path("defaultMethod").asText();
            require(Set.of("A", "B").contains(preferred));
            JsonNode multiplier = input.path("policy").path("multiplier");
            require(multiplier.isNumber() && f.path("multiplier").isNumber()
                    && multiplier.decimalValue().compareTo(f.path("multiplier").decimalValue()) == 0);
            f.put("methodFallback", !preferred.equals(f.path("method").asText()));
            f.put("startStockEstimated", h.start().isAfter(h.origin()));
            require(input.path("noDateReceipts").isArray());
            BigDecimal noDate = BigDecimal.ZERO;
            for (JsonNode receipt : input.path("noDateReceipts")) {
                require(receipt.path("productId").isIntegralNumber() && receipt.path("productId").asLong() > 0);
                if (receipt.path("productId").asLong() != h.product()) continue;
                var quantity = receipt.path("quantity");
                require(quantity.isIntegralNumber() && quantity.decimalValue().signum() > 0);
                noDate = noDate.add(quantity.decimalValue());
            }
            require(noDate.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0);
            f.put("noDateReceiptQty", noDate);
            var scenarios = f.putArray("scenarios");
            jdbc.query("""
                    SELECT scenario_type,scenario_qty,is_duplicate,expected_depletion_on,expected_ending_stock,
                           unmet_demand_qty,post_arrival_risk FROM scenario_results
                    WHERE result_id=? AND scenario_type IN ('P80','P100','P120') ORDER BY scenario_type
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                var s = scenarios.addObject().put("type", rs.getString(1)).put("qty", rs.getLong(2)).put("isDuplicate", rs.getBoolean(3));
                if (rs.getDate(4) == null) s.putNull("expectedDepletionOn"); else s.put("expectedDepletionOn", rs.getDate(4).toString());
                s.set("expectedEndingStock", mapper.valueToTree(rs.getBigDecimal(5)));
                s.set("unmetDemandQty", mapper.valueToTree(rs.getBigDecimal(6)));
                s.set("postArrivalRisk", mapper.valueToTree(rs.getBigDecimal(7)));
            }, resultId);
            require(h.validDays() != null && h.validDays() >= 7 && f.path("riskSampleSize").isNumber()
                    && f.path("riskSampleSize").decimalValue().signum() >= 0);
            boolean anyRisk = f.hasNonNull("preArrivalRisk") || f.hasNonNull("riskBasis");
            boolean noPostRisk = true;
            for (var s : scenarios) noPostRisk &= !s.hasNonNull("postArrivalRisk");
            // engine/calculate.py _apply_risk: 7..13-day suppression precedes sample selection.
            if (h.validDays() <= 13) {
                require(!anyRisk && noPostRisk); f.put("riskStatus", "LOW_GRADE");
            } else if (!anyRisk) {
                require(noPostRisk && f.path("riskSampleSize").decimalValue().signum() >= 0);
                f.put("riskStatus", "INSUFFICIENT_SAMPLE");
            } else {
                require(f.path("preArrivalRisk").isNumber() && Set.of("SKU", "POOLED").contains(f.path("riskBasis").asText()));
                f.put("riskStatus", "OK");
            }
            return renderer.prepareHistorical(f, h.origin().toString());
        } catch (ProductException | org.springframework.dao.DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ProductException.insightInputUnavailable();
        }
    }

    private record Historical(ObjectNode facts, JsonNode input, long product, long warehouse, LocalDate origin,
                              LocalDate start, LocalDate asOf, Integer validDays, String runStatus) { }
    private static void require(boolean valid) { if (!valid) throw ProductException.insightInputUnavailable(); }
}
