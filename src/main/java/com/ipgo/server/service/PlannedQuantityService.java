package com.ipgo.server.service;

import com.ipgo.server.dto.request.PlannedQuantityRequest;
import com.ipgo.server.dto.response.PlannedQuantities;
import com.ipgo.server.entity.Product;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.ProductRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// #119 내 입고량. 새 계산이 끝나도 지우지 않고, 화면이 calculationRunId로 "지난 계산 기준"을 판단한다.
// 대시보드 · 상품 상세 응답은 바꾸지 않는다. 화면은 GET 목록 한 번으로 상품별 값을 붙인다.
@Service
public class PlannedQuantityService {
    private final JdbcTemplate jdbc;
    private final WarehouseService warehouses;
    private final ProductRepository products;

    public PlannedQuantityService(JdbcTemplate jdbc, WarehouseService warehouses, ProductRepository products) {
        this.jdbc = jdbc;
        this.warehouses = warehouses;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public PlannedQuantities list(long userId) {
        return new PlannedQuantities(jdbc.query("""
                SELECT q.product_id, q.quantity, q.source, q.decided_at, q.calculation_run_id
                FROM product_planned_quantities q JOIN products p ON p.id = q.product_id
                WHERE p.user_id = ? ORDER BY q.product_id
                """, PlannedQuantityService::item, userId));
    }

    @Transactional
    public PlannedQuantities.Item save(long userId, long productId, PlannedQuantityRequest request) {
        warehouses.lockDefault(userId);
        owned(userId, productId);
        checkRun(userId, request.calculationRunId());
        Object[] values = {request.quantity(), request.source(), request.calculationRunId(), productId};
        int updated = jdbc.update("""
                UPDATE product_planned_quantities SET quantity = ?, source = ?, calculation_run_id = ?,
                    decided_at = CURRENT_TIMESTAMP WHERE product_id = ?
                """, values);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO product_planned_quantities (quantity, source, calculation_run_id, product_id)
                    VALUES (?, ?, ?, ?)
                    """, values);
        }
        return jdbc.queryForObject("""
                SELECT product_id, quantity, source, decided_at, calculation_run_id
                FROM product_planned_quantities WHERE product_id = ?
                """, PlannedQuantityService::item, productId);
    }

    @Transactional
    public void delete(long userId, long productId) {
        warehouses.lockDefault(userId);
        owned(userId, productId);
        jdbc.update("DELETE FROM product_planned_quantities WHERE product_id = ?", productId);
    }

    private void owned(long userId, long productId) {
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();
    }

    // 기준 계산은 이 회원 창고의 끝난(DONE) 계산이어야 한다. 없거나 끝나지 않았으면 입력 오류, 다른 회원 것이면 403.
    private void checkRun(long userId, long runId) {
        List<Object[]> rows = jdbc.query("""
                SELECT w.user_id, r.status FROM calculation_runs r JOIN warehouses w ON w.id = r.warehouse_id WHERE r.id = ?
                """, (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, runId);
        if (rows.isEmpty()) throw ProductException.invalidField("calculationRunId");
        if (!Long.valueOf(userId).equals(rows.getFirst()[0])) throw ProductException.forbidden();
        if (!"DONE".equals(rows.getFirst()[1])) throw ProductException.invalidField("calculationRunId");
    }

    private static PlannedQuantities.Item item(ResultSet rs, int row) throws SQLException {
        Timestamp decided = rs.getTimestamp(4);
        return new PlannedQuantities.Item(rs.getLong(1), rs.getLong(2), rs.getString(3),
                decided.toInstant().atZone(DashboardService.SEOUL).toOffsetDateTime(), rs.getLong(5));
    }
}
