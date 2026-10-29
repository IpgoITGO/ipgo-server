package com.ipgo.server.service;

import com.ipgo.server.dto.request.PlannedReceiptRequest;
import com.ipgo.server.dto.request.PlannedReceiptUpdateRequest;
import com.ipgo.server.dto.response.PlannedReceiptCreated;
import com.ipgo.server.dto.response.PlannedReceiptItem;
import com.ipgo.server.entity.PlannedReceipt;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.PlannedReceiptRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.WarehouseRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 기존 발주 물량 등록 · 조회 · 수정 · 취소(R1~R3). 저장 뒤 계산 시작은 계산 엔진 연동 때 붙인다.
@Service
public class PlannedReceiptService {
    private final PlannedReceiptRepository receipts;
    private final ProductRepository products;
    private final WarehouseService warehouses;
    private final WarehouseRepository warehouseRepository;

    public PlannedReceiptService(PlannedReceiptRepository receipts, ProductRepository products,
            WarehouseService warehouses, WarehouseRepository warehouseRepository) {
        this.receipts = receipts;
        this.products = products;
        this.warehouses = warehouses;
        this.warehouseRepository = warehouseRepository;
    }

    @Transactional(readOnly = true)
    public PlannedReceiptItem.Page list(long userId) {
        List<PlannedReceipt> active = warehouses.findDefault(userId)
                .map(warehouse -> receipts.findActive(warehouse.getId()))
                .orElse(List.of());
        Map<Long, Product> byId = products.findAllById(active.stream().map(PlannedReceipt::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<PlannedReceiptItem> items = active.stream()
                .map(receipt -> PlannedReceiptItem.from(receipt, byId.get(receipt.getProductId()))).toList();
        return new PlannedReceiptItem.Page(items, items.size());
    }

    @Transactional
    public PlannedReceiptCreated create(long userId, PlannedReceiptRequest request) {
        ownedProduct(userId, request.productId());
        Warehouse warehouse = warehouses.lockDefault(userId);
        PlannedReceipt saved = receipts.save(new PlannedReceipt(request.productId(), warehouse.getId(),
                request.quantity(), request.expectedOn()));
        return PlannedReceiptCreated.from(saved);
    }

    @Transactional
    public PlannedReceiptItem update(long userId, long receiptId, PlannedReceiptUpdateRequest request) {
        PlannedReceipt receipt = ownedReceipt(userId, receiptId);
        receipt.update(request.quantity(), request.expectedOn());
        return PlannedReceiptItem.from(receipt, ownedProduct(userId, receipt.getProductId()));
    }

    @Transactional
    public void cancel(long userId, long receiptId) {
        ownedReceipt(userId, receiptId).cancel();
    }

    private Product ownedProduct(long userId, long productId) {
        Product product = products.findById(productId).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();
        return product;
    }

    // 취소한 발주는 없는 것으로 본다.
    private PlannedReceipt ownedReceipt(long userId, long receiptId) {
        PlannedReceipt receipt = receipts.findById(receiptId)
                .filter(found -> !found.isCancelled())
                .orElseThrow(ProductException::notFound);
        Warehouse warehouse = warehouseRepository.findById(receipt.getWarehouseId()).orElseThrow();
        if (!warehouse.getUserId().equals(userId)) throw ProductException.forbidden();
        return receipt;
    }
}
