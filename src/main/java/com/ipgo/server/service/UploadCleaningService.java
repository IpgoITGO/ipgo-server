package com.ipgo.server.service;

import com.ipgo.server.dto.CleanedStockUnit;
import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.dto.UploadProcessingReport;
import com.ipgo.server.entity.InventoryUnit;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.repository.InventoryUnitRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.repository.WarehouseRepository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내부 결과 저장 경계. 외부 HTTP callback은 열지 않는다. 수신 adapter는 DB commit 뒤에만 ACK해야 한다.
@Service
public class UploadCleaningService {
    public enum Outcome { APPLIED, ALREADY_FINISHED }
    public enum Failure {
        INVALID_SOURCE("수불부 형식을 확인하고 다시 올려 주세요."),
        ENGINE_UNAVAILABLE("정제 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
        INVALID_RESULT("정제 결과를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        private final String message;
        Failure(String message) { this.message = message; }
    }

    private final UploadFileRepository uploads;
    private final WarehouseRepository warehouses;
    private final ProductRepository products;
    private final InventoryUnitRepository units;
    private final ProductRegistrationService registration;
    private final JdbcTemplate jdbc;
    private final Validator validator;

    public UploadCleaningService(UploadFileRepository uploads, WarehouseRepository warehouses,
            ProductRepository products, InventoryUnitRepository units, ProductRegistrationService registration,
            JdbcTemplate jdbc, Validator validator) {
        this.uploads = uploads;
        this.warehouses = warehouses;
        this.products = products;
        this.units = units;
        this.registration = registration;
        this.jdbc = jdbc;
        this.validator = validator;
    }

    @Transactional
    public Outcome complete(long uploadId, String jobId, CleaningResult result) {
        UploadFile upload = lock(uploadId, jobId);
        if (!upload.isProcessing()) return Outcome.ALREADY_FINISHED;
        validate(upload, result);
        long userId = warehouses.findById(upload.getWarehouseId()).orElseThrow().getUserId();
        Map<String, CleaningResult.CleanProduct> inputProducts = new HashMap<>();
        result.products().forEach(p -> inputProducts.put(p.skuBarcode(), p));
        registration.register(userId, upload.getWarehouseId(), result.inventoryUnits().stream()
                .map(u -> new CleanedStockUnit(u.skuBarcode(), inputProducts.get(u.skuBarcode()).skuName(),
                        u.sourceInventoryKey(), u.receivedOn())).toList());

        Map<String, Product> productMap = new HashMap<>();
        products.findByUserIdAndSkuBarcodeIn(userId, inputProducts.keySet())
                .forEach(p -> productMap.put(p.getSkuBarcode(), p));
        // 셀러가 입력한 원가·이름·사용 여부는 정제 결과로 덮어쓰지 않는다.
        for (var input : result.products()) {
            Product product = productMap.get(input.skuBarcode());
            require(product != null);
            if (product.getUnitCost() == null && input.unitCost() != null) product.update(null, input.unitCost(), null);
        }
        products.flush();
        units.flush();
        Map<UnitKey, InventoryUnit> unitMap = new HashMap<>();
        units.findByWarehouseIdAndSourceInventoryKeyIn(upload.getWarehouseId(), result.inventoryUnits().stream()
                        .map(CleaningResult.Unit::sourceInventoryKey).toList())
                .forEach(u -> unitMap.put(new UnitKey(u.getSourceInventoryKey(), u.getReceivedOn()), u));
        for (var input : result.inventoryUnits()) {
            var unit = unitMap.get(new UnitKey(input.sourceInventoryKey(), input.receivedOn()));
            // 기존 LOT를 다른 상품에 붙이는 결과는 전체 rollback한다.
            require(unit != null && unit.getProductId().equals(productMap.get(input.skuBarcode()).getId()));
        }

        jdbc.batchUpdate("""
                INSERT INTO daily_stock_records (upload_file_id, inventory_unit_id, stock_date, opening_qty,
                    inbound_qty, outbound_qty, adjustment_qty, closing_qty, source_sheet, source_row)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, result.unitDailyRecords(), 500, (ps, row) -> {
                    ps.setLong(1, uploadId);
                    ps.setLong(2, unitMap.get(new UnitKey(row.sourceInventoryKey(), row.receivedOn())).getId());
                    ps.setObject(3, row.stockDate());
                    ps.setLong(4, row.openingQty()); ps.setLong(5, row.inboundQty());
                    ps.setLong(6, row.outboundQty()); ps.setLong(7, row.adjustmentQty());
                    ps.setLong(8, row.closingQty()); ps.setString(9, row.sourceSheet()); ps.setInt(10, row.sourceRow());
                });
        jdbc.batchUpdate("""
                INSERT INTO product_daily_stocks (product_id, upload_file_id, stock_date, opening_qty,
                    inbound_qty, outbound_qty, adjustment_qty, closing_qty, is_valid_observation, observation_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, result.productDailyStocks(), 500, (ps, row) -> {
                    ps.setLong(1, productMap.get(row.skuBarcode()).getId()); ps.setLong(2, uploadId);
                    ps.setObject(3, row.stockDate());
                    ps.setLong(4, row.openingQty()); ps.setLong(5, row.inboundQty());
                    ps.setLong(6, row.outboundQty()); ps.setLong(7, row.adjustmentQty());
                    ps.setLong(8, row.closingQty()); ps.setBoolean(9, row.isValidObservation());
                    ps.setString(10, row.observationNote());
                });

        var excluded = result.validation().excludedSkus().stream().map(e -> {
            var p = productMap.get(e.skuBarcode());
            return new UploadProcessingReport.ExcludedSku(p.getId(), p.getSkuBarcode(), p.getSkuName(), e.date(), e.reason());
        }).toList();
        upload.complete(result.periodEnd(), new UploadProcessingReport(result.asOfDate(),
                new UploadProcessingReport.Validation(result.validation().checkedSkuCount(),
                        result.validation().targetSkuCount(), excluded), result.estimatedReviewCycleDays(), null));
        return Outcome.APPLIED;
    }

    @Transactional
    public Outcome fail(long uploadId, String jobId, Failure failure) {
        Objects.requireNonNull(failure);
        UploadFile upload = lock(uploadId, jobId);
        if (!upload.isProcessing()) return Outcome.ALREADY_FINISHED;
        upload.fail(new UploadProcessingReport.Failure(failure.name(), failure.message));
        return Outcome.APPLIED;
    }

    private UploadFile lock(long uploadId, String jobId) {
        // 업로드와 같은 창고 → 업로드 순서로 잠가 동시 결과 저장과 업로드의 교착을 피한다.
        long warehouseId = uploads.warehouseId(uploadId).orElseThrow(() -> new IllegalArgumentException("Unknown upload"));
        warehouses.findByIdForUpdate(warehouseId).orElseThrow();
        UploadFile upload = uploads.findByIdForUpdate(uploadId).orElseThrow();
        if (jobId == null || jobId.isBlank() || !jobId.equals(upload.getJobId())) {
            throw new IllegalArgumentException("Cleaning job does not match upload");
        }
        return upload;
    }

    private void validate(UploadFile upload, CleaningResult r) {
        require(r != null && validator.validate(r).isEmpty());
        // 파일명으로 확정한 월 안의 자료만 허용한다. 여러 월 묶음 계약은 아직 적용하지 않는다.
        require(!r.periodStart().isBefore(upload.getPeriodStart()) && !r.periodStart().isAfter(r.periodEnd())
                && !r.periodEnd().isAfter(upload.getPeriodEnd()) && r.asOfDate().equals(r.periodEnd()));
        Map<String, CleaningResult.CleanProduct> productMap = new HashMap<>();
        for (var p : r.products()) {
            require(p.skuBarcode().equals(p.skuBarcode().strip()) && productMap.put(p.skuBarcode(), p) == null);
            require(p.unitCost() == null || p.unitCost() <= 9_999_999_999_999_999L);
        }
        Map<UnitKey, String> unitProducts = new HashMap<>();
        for (var u : r.inventoryUnits()) {
            require(u.sourceInventoryKey().equals(u.sourceInventoryKey().strip()) && productMap.containsKey(u.skuBarcode()));
            require(unitProducts.put(new UnitKey(u.sourceInventoryKey(), u.receivedOn()), u.skuBarcode()) == null);
        }
        require(new HashSet<>(unitProducts.values()).equals(productMap.keySet()));
        Set<UnitDate> unitDates = new HashSet<>();
        for (var d : r.unitDailyRecords()) {
            UnitKey key = new UnitKey(d.sourceInventoryKey(), d.receivedOn());
            require(d.uploadFileId().equals(upload.getId()) && unitProducts.containsKey(key)
                    && inPeriod(d.stockDate(), r) && !d.stockDate().isBefore(d.receivedOn())
                    && unitDates.add(new UnitDate(key, d.stockDate())));
            quantities(d.openingQty(), d.inboundQty(), d.outboundQty(), d.adjustmentQty(), d.closingQty());
        }
        Set<ProductDate> productDates = new HashSet<>();
        for (var d : r.productDailyStocks()) {
            require(d.uploadFileId().equals(upload.getId()) && productMap.containsKey(d.skuBarcode())
                    && inPeriod(d.stockDate(), r) && productDates.add(new ProductDate(d.skuBarcode(), d.stockDate())));
            quantities(d.openingQty(), d.inboundQty(), d.outboundQty(), d.adjustmentQty(), d.closingQty());
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(r.periodStart(), r.periodEnd()) + 1;
        require(productDates.size() == days * productMap.size());
        Set<String> excluded = new HashSet<>();
        Set<ProductDate> exclusionDates = new HashSet<>();
        for (var e : r.validation().excludedSkus()) {
            require(productMap.containsKey(e.skuBarcode()) && inPeriod(e.date(), r)
                    && exclusionDates.add(new ProductDate(e.skuBarcode(), e.date())));
            excluded.add(e.skuBarcode());
        }
        require(r.validation().checkedSkuCount() == productMap.size()
                && r.validation().targetSkuCount() == productMap.size() - excluded.size());
    }

    private static boolean inPeriod(LocalDate date, CleaningResult r) {
        return !date.isBefore(r.periodStart()) && !date.isAfter(r.periodEnd());
    }
    private static void quantities(long... quantities) {
        for (long q : quantities) require(q >= -999_999_999_999_999L && q <= 999_999_999_999_999L);
    }
    private static void require(boolean valid) {
        // 결과 원문에는 개인정보가 들어갈 수 있어 예외 메시지에 포함하지 않는다.
        if (!valid) throw new IllegalArgumentException("Invalid cleaning result");
    }
    private record UnitKey(String sourceInventoryKey, LocalDate receivedOn) { }
    private record UnitDate(UnitKey unit, LocalDate date) { }
    private record ProductDate(String barcode, LocalDate date) { }
}
