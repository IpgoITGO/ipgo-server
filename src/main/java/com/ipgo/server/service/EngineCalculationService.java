package com.ipgo.server.service;

import com.ipgo.server.dto.response.ProductSettings;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.repository.PlannedReceiptRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.repository.ReplenishmentSettingRepository;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.repository.WarehouseRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Creates immutable, owner-scoped inputs; all domain triggers serialize on the warehouse lock. */
@Service
public class EngineCalculationService {
    private final EngineJobs jobs;
    private final WarehouseRepository warehouses;
    private final UploadFileRepository uploads;
    private final ProductRepository products;
    private final ReplenishmentSettingRepository settings;
    private final PlannedReceiptRepository receipts;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final EntityManager entities;
    private final Clock clock;

    public EngineCalculationService(EngineJobs jobs, WarehouseRepository warehouses, UploadFileRepository uploads,
            ProductRepository products, ReplenishmentSettingRepository settings, PlannedReceiptRepository receipts,
            JdbcTemplate jdbc, JsonMapper mapper, EntityManager entities, Clock clock) {
        this.jobs = jobs; this.warehouses = warehouses; this.uploads = uploads; this.products = products;
        this.settings = settings; this.receipts = receipts; this.jdbc = jdbc; this.mapper = mapper;
        this.entities = entities; this.clock = clock;
    }

    @Transactional
    public Long request(long userId, long warehouseId, String trigger, boolean force) {
        if (!jobs.enabled()) return null;
        var warehouse = warehouses.findByIdForUpdate(warehouseId).orElseThrow();
        if (!warehouse.getUserId().equals(userId)) throw new IllegalArgumentException("Engine owner mismatch");
        // S1 is a suggestion. Do not invent a review cycle before the seller confirms settings.
        if (!warehouse.hasConfirmedDefaults()) return null;
        entities.flush();
        List<UploadFile> sources = uploads.findLatestPerPeriod(warehouseId).stream()
                .filter(u -> "DONE".equals(u.getStatus())).sorted(Comparator.comparing(UploadFile::getPeriodStart)).toList();
        if (sources.isEmpty()) return null;
        LocalDate asOf = sources.stream().map(UploadFile::getPeriodEnd).max(LocalDate::compareTo).orElseThrow();
        LocalDate today = LocalDate.now(clock.withZone(DashboardService.SEOUL));
        if (today.isBefore(asOf)) return null;
        var active = products.findByUserIdAndSkuBarcodeIn(userId, jdbc.queryForList(
                "SELECT sku_barcode FROM products WHERE user_id = ? AND is_active = TRUE ORDER BY id", String.class, userId))
                .stream().filter(p -> p.isActive()).sorted(Comparator.comparing(p -> p.getId())).toList();
        if (active.isEmpty()) return null;
        var overrides = new HashMap<Long, com.ipgo.server.entity.ReplenishmentSetting>();
        settings.findByProductIdIn(active.stream().map(p -> p.getId()).toList()).forEach(s -> overrides.put(s.getProductId(), s));
        ProductSettings defaults = ProductSettings.defaults(Optional.of(warehouse));
        List<Map<String, Object>> inputSettings = new ArrayList<>();
        for (var product : active) {
            var setting = defaults.applying(overrides.get(product.getId()));
            // Missing cost/settings remains NOT_CALCULABLE in the engine, rather than silently using zero cost.
            if (setting.leadTimeDays() != null && setting.reviewCycleDays() != null && setting.orderUnitQty() != null
                    && product.getUnitCost() != null) {
                inputSettings.add(Map.of("productId", product.getId(), "leadTimeDays", setting.leadTimeDays(),
                        "reviewCycleDays", setting.reviewCycleDays(), "orderUnitQty", setting.orderUnitQty(),
                        "unitCost", product.getUnitCost()));
            }
        }
        Map<String, String> excluded = new LinkedHashMap<>();
        // Use the selected latest file's validation, never an overwritten revision.
        for (UploadFile source : sources) {
            if (source.getValidationReport() != null && source.getValidationReport().validation() != null)
                source.getValidationReport().validation().excludedSkus().forEach(e -> excluded.put("" + e.productId(), e.reason()));
        }
        var ids = active.stream().map(p -> p.getId()).toList();
        List<Map<String, Object>> planned = receipts.findActive(warehouseId).stream()
                .filter(r -> r.isReflected() && ids.contains(r.getProductId()) && r.getExpectedOn() != null)
                // Past expected dates are not moved to today or counted as future arrivals.
                .filter(r -> r.getExpectedOn().isAfter(asOf))
                .sorted(Comparator.comparing(r -> r.getId()))
                .map(r -> Map.<String,Object>of("productId", r.getProductId(), "quantity", r.getQuantity(), "expectedOn", r.getExpectedOn())).toList();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", userId); request.put("warehouseId", warehouseId);
        request.put("uploadFileIds", sources.stream().map(UploadFile::getId).toList());
        request.put("asOfDate", asOf); request.put("today", today);
        request.put("productIds", ids.stream().filter(id -> !excluded.containsKey(""+id)).toList());
        request.put("policy", Map.of("defaultMethod", "B", "multiplier", 1.0));
        request.put("settings", inputSettings.stream().filter(s -> !excluded.containsKey(""+s.get("productId"))).toList());
        request.put("plannedReceipts", planned);
        String hash = hash(mapper.writeValueAsString(Map.of("request", request, "excluded", excluded)));
        if (!force) {
            List<Long> same = jdbc.queryForList("""
                    SELECT id FROM calculation_runs WHERE warehouse_id = ? AND input_hash = ?
                    AND status IN ('QUEUED','RUNNING','DONE') ORDER BY id DESC FETCH FIRST 1 ROWS ONLY
                    """, Long.class, warehouseId, hash);
            if (!same.isEmpty()) return same.getFirst();
        }
        String jobId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO calculation_runs(warehouse_id,run_type,as_of_date,status,job_id,engine_version,
                    policy_version,selection_snapshot,created_at,input_hash)
                VALUES (?, ?, ?, 'QUEUED', ?, 'pending', 'B-1.00', CAST('{}' AS JSONB), CURRENT_TIMESTAMP, ?)
                """, warehouseId, trigger, asOf, jobId, hash);
        long runId = jdbc.queryForObject("SELECT id FROM calculation_runs WHERE job_id = ?", Long.class, jobId);
        request.put("runId", runId);
        Map<String, Object> snapshot = Map.of("request", request, "excluded", excluded, "productIds", ids);
        jdbc.update("UPDATE calculation_runs SET selection_snapshot = CAST(? AS JSONB) WHERE id = ?",
                mapper.writeValueAsString(snapshot), runId);
        for (UploadFile source : sources) jdbc.update("INSERT INTO run_source_files(run_id,upload_file_id) VALUES (?,?)", runId, source.getId());
        jobs.enqueue(jobId, "CALCULATE", runId, warehouseId, request);
        return runId;
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
