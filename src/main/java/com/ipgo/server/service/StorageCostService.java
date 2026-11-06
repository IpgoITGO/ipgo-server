package com.ipgo.server.service;

import com.ipgo.server.dto.request.ProductBoxRequest;
import com.ipgo.server.dto.request.StorageCostRequest;
import com.ipgo.server.dto.response.StorageCost;
import com.ipgo.server.entity.Product;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// #111 보관 비용 설정(창고 보관 요율 · 상품 상자 크기). 계산 입력이 아니어서 저장해도 재계산하지 않는다.
// 같은 회원의 저장은 기본 창고 잠금으로 차례로 처리한다.
@Service
public class StorageCostService {
    private final JdbcTemplate jdbc;
    private final WarehouseService warehouses;
    private final ProductRepository products;

    public StorageCostService(JdbcTemplate jdbc, WarehouseService warehouses, ProductRepository products) {
        this.jdbc = jdbc;
        this.warehouses = warehouses;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public StorageCost get(long userId) {
        BigDecimal rate = warehouses.findDefault(userId).map(w -> jdbc.queryForObject(
                "SELECT storage_rate_per_cbm FROM warehouses WHERE id = ?", BigDecimal.class, w.getId())).orElse(null);
        List<StorageCost.Box> boxes = jdbc.query("""
                SELECT b.product_id, b.width_cm, b.depth_cm, b.height_cm FROM product_boxes b
                JOIN products p ON p.id = b.product_id WHERE p.user_id = ? ORDER BY b.product_id
                """, (rs, n) -> new StorageCost.Box(rs.getLong(1), number(rs.getBigDecimal(2)),
                        number(rs.getBigDecimal(3)), number(rs.getBigDecimal(4))), userId);
        return new StorageCost(number(rate), boxes);
    }

    @Transactional
    public StorageCost saveRate(long userId, StorageCostRequest request) {
        var warehouse = warehouses.lockDefault(userId);
        jdbc.update("UPDATE warehouses SET storage_rate_per_cbm = ? WHERE id = ?", request.ratePerCbm(), warehouse.getId());
        return get(userId);
    }

    @Transactional
    public StorageCost.Box saveBox(long userId, long productId, ProductBoxRequest request) {
        warehouses.lockDefault(userId);
        owned(userId, productId);
        Object[] values = {request.width(), request.depth(), request.height(), productId};
        int updated = jdbc.update("""
                UPDATE product_boxes SET width_cm = ?, depth_cm = ?, height_cm = ?, updated_at = CURRENT_TIMESTAMP
                WHERE product_id = ?
                """, values);
        if (updated == 0) {
            jdbc.update("INSERT INTO product_boxes (width_cm, depth_cm, height_cm, product_id) VALUES (?, ?, ?, ?)", values);
        }
        return new StorageCost.Box(productId, number(request.width()), number(request.depth()), number(request.height()));
    }

    @Transactional
    public void deleteBox(long userId, long productId) {
        warehouses.lockDefault(userId);
        owned(userId, productId);
        jdbc.update("DELETE FROM product_boxes WHERE product_id = ?", productId);
    }

    private void owned(long userId, long productId) {
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
