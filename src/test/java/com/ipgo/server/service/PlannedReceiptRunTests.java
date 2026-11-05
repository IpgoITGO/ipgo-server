package com.ipgo.server.service;

import com.ipgo.server.dto.request.PlannedReceiptUpdateRequest;
import com.ipgo.server.entity.PlannedReceipt;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.repository.PlannedReceiptRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.WarehouseRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannedReceiptRunTests {
    final PlannedReceiptRepository receipts = mock(PlannedReceiptRepository.class);
    final ProductRepository products = mock(ProductRepository.class);
    final WarehouseService warehouses = mock(WarehouseService.class);
    final WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
    final EngineCalculationService calculations = mock(EngineCalculationService.class);
    final PlannedReceiptService service = new PlannedReceiptService(receipts, products, warehouses, warehouseRepository, calculations);

    void arrange() {
        var warehouse = mock(Warehouse.class);
        when(warehouse.getId()).thenReturn(9L);
        when(warehouse.getUserId()).thenReturn(7L);
        when(receipts.warehouseId(1L)).thenReturn(Optional.of(9L));
        when(warehouseRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.findById(9L)).thenReturn(Optional.of(warehouse));
        var receipt = mock(PlannedReceipt.class);
        when(receipt.getWarehouseId()).thenReturn(9L);
        when(receipt.getProductId()).thenReturn(3L);
        when(receipts.findById(1L)).thenReturn(Optional.of(receipt));
        var product = mock(Product.class);
        when(product.getUserId()).thenReturn(7L);
        when(products.findById(3L)).thenReturn(Optional.of(product));
        when(calculations.request(7, 9, "RECEIPTS", false)).thenReturn(71L);
    }

    @Test void forwardsActualEngineRunFromUpdate() {
        arrange();
        assertEquals(71L, service.update(7, 1, new PlannedReceiptUpdateRequest(10L, null)).runId());
        verify(calculations, times(1)).request(7, 9, "RECEIPTS", false);
    }

    @Test void forwardsActualEngineRunFromCancel() {
        arrange();
        assertEquals(71L, service.cancel(7, 1));
        verify(calculations, times(1)).request(7, 9, "RECEIPTS", false);
    }
}
