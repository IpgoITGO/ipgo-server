package com.ipgo.server.product;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 정제 결과를 받아 상품(바코드 1개 = 1행)과 재고관리단위(LOT)를 자동 등록한다 (BE-05).
@Service
public class ProductRegistrationService {
    private final WarehouseRepository warehouses;
    private final ProductRepository products;
    private final InventoryUnitRepository inventoryUnits;

    public ProductRegistrationService(WarehouseRepository warehouses, ProductRepository products,
            InventoryUnitRepository inventoryUnits) {
        this.warehouses = warehouses;
        this.products = products;
        this.inventoryUnits = inventoryUnits;
    }

    @Transactional
    public RegistrationResult register(long userId, long warehouseId, List<CleanedStockUnit> rows) {
        Warehouse warehouse = warehouses.findById(warehouseId).orElseThrow(ProductException::notFound);
        if (!warehouse.getUserId().equals(userId)) throw ProductException.forbidden();

        // 1) 상품: 처음 나온 바코드만 새로 만든다. 이미 있는 상품의 이름은 셀러가 고쳤을 수 있어서 덮어쓰지 않는다.
        Map<String, String> namesByBarcode = new LinkedHashMap<>();
        rows.forEach(row -> namesByBarcode.putIfAbsent(row.skuBarcode(), row.skuName()));

        Map<String, Product> productsByBarcode = new HashMap<>();
        products.findByUserIdAndSkuBarcodeIn(userId, namesByBarcode.keySet())
                .forEach(product -> productsByBarcode.put(product.getSkuBarcode(), product));

        List<Product> newProducts = namesByBarcode.entrySet().stream()
                .filter(entry -> !productsByBarcode.containsKey(entry.getKey()))
                .map(entry -> new Product(userId, entry.getKey(), entry.getValue()))
                .toList();
        products.saveAll(newProducts).forEach(product -> productsByBarcode.put(product.getSkuBarcode(), product));

        // 2) 재고관리단위: sku_mixed_id + 입고일이 처음이면 새로 만든다. 입고일이 다르면 다른 단위다.
        Set<String> keys = new HashSet<>();
        rows.forEach(row -> keys.add(row.sourceInventoryKey()));
        Set<UnitKey> seen = new HashSet<>();
        inventoryUnits.findByWarehouseIdAndSourceInventoryKeyIn(warehouseId, keys)
                .forEach(unit -> seen.add(new UnitKey(unit.getSourceInventoryKey(), unit.getReceivedOn())));

        List<InventoryUnit> newUnits = new ArrayList<>();
        for (CleanedStockUnit row : rows) {
            if (seen.add(new UnitKey(row.sourceInventoryKey(), row.receivedOn()))) {
                Long productId = productsByBarcode.get(row.skuBarcode()).getId();
                newUnits.add(new InventoryUnit(productId, warehouseId, row.sourceInventoryKey(), row.receivedOn()));
            }
        }
        inventoryUnits.saveAll(newUnits);

        return new RegistrationResult(newProducts.size(), newUnits.size());
    }

    private record UnitKey(String sourceInventoryKey, LocalDate receivedOn) { }
}
