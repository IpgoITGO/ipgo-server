package com.ipgo.server.service;

import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.dto.response.UploadResult.MatchedReceipt;
import com.ipgo.server.dto.response.UploadResult.ReceiptMatching;
import com.ipgo.server.dto.response.UploadResult.ReceiptMatchingIssue;
import com.ipgo.server.entity.PlannedReceipt;
import com.ipgo.server.entity.Product;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.repository.PlannedReceiptRepository;
import com.ipgo.server.repository.UploadFileRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 정제 저장과 같은 transaction/창고 잠금 안에서만 입고를 배정한다. */
@Service
public class ReceiptMatchingService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final PlannedReceiptRepository receipts;
    private final UploadFileRepository uploads;
    private final JsonMapper mapper;

    public ReceiptMatchingService(JdbcTemplate jdbc, PlannedReceiptRepository receipts,
            UploadFileRepository uploads, JsonMapper mapper) {
        this.jdbc = jdbc; this.receipts = receipts; this.uploads = uploads; this.mapper = mapper;
    }

    public record Report(List<MatchedReceipt> matchedReceipts, ReceiptMatching receiptMatching) {
        static Report empty(String status) {
            return new Report(List.of(), new ReceiptMatching(status, List.of()));
        }
    }
    private record Day(long productId, LocalDate date) { }
    private record Observation(long quantity, boolean valid) { }
    private record Lot(String key, LocalDate receivedOn) { }
    private record Event(long id, Day day, long quantity, boolean valid, boolean review) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public void match(UploadFile upload, long userId, CleaningResult result, Map<String, Product> products) {
        boolean current = uploads.findLatestPerPeriod(upload.getWarehouseId()).stream()
                .anyMatch(u -> u.getId().equals(upload.getId()));
        if (!current) {
            save(upload.getId(), Report.empty("SUPERSEDED"));
            return;
        }
        Map<Day, Observation> observed = observations(result, products);
        Map<Day, Event> existing = new HashMap<>();
        // 최신 revision이 상품/날짜를 없애거나 자료 끝을 줄인 경우도 비교한다.
        jdbc.query("""
                SELECT id,product_id,stock_date,quantity,valid_observation,review_required
                FROM receipt_matching_events WHERE warehouse_id = ? AND stock_date >= ? AND stock_date < ?
                """, (rs, n) -> new Event(rs.getLong(1), new Day(rs.getLong(2), rs.getObject(3, LocalDate.class)),
                        rs.getLong(4), rs.getBoolean(5), rs.getBoolean(6)),
                upload.getWarehouseId(), upload.getPeriodStart(), upload.getPeriodStart().plusMonths(1))
                .forEach(e -> existing.put(e.day(), e));
        existing.keySet().forEach(day -> observed.putIfAbsent(day, new Observation(0, false)));
        List<PlannedReceipt> candidates = receipts.findActive(upload.getWarehouseId()).stream()
                .filter(r -> !r.isReceived())
                .sorted(Comparator.comparing(PlannedReceipt::getExpectedOn, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PlannedReceipt::getCreatedAt).thenComparing(PlannedReceipt::getId))
                .toList();
        Map<Long, String> names = new HashMap<>();
        products.values().forEach(p -> names.put(p.getId(), p.getSkuName()));
        List<MatchedReceipt> matched = new ArrayList<>();
        List<ReceiptMatchingIssue> issues = new ArrayList<>();
        for (var entry : observed.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(Day::date).thenComparingLong(Day::productId))).toList()) {
            Day day = entry.getKey();
            Observation observation = entry.getValue();
            Event old = existing.get(day);
            if (old == null && observation.quantity() == 0) continue;
            Event event = old == null ? create(upload, userId, day, observation) : old;
            List<MatchedReceipt> allocations = allocations(event.id());
            boolean changed = old != null && (old.quantity() != observation.quantity() || old.valid() != observation.valid());
            if (event.review() || (changed && !allocations.isEmpty())) {
                jdbc.update("UPDATE receipt_matching_events SET review_required = TRUE WHERE id = ?", event.id());
                issues.add(new ReceiptMatchingIssue(day.productId(), day.date(), "SOURCE_CHANGED"));
            } else if (old == null || changed) {
                jdbc.update("""
                        UPDATE receipt_matching_events SET quantity = ?,valid_observation = ?,source_upload_file_id = ? WHERE id = ?
                        """, observation.quantity(), observation.valid(), upload.getId(), event.id());
                allocate(upload, event.id(), day, observation, candidates, names, issues);
            } else {
                // 같은 입고를 재평가해 나중에 변경한 발주에 잔량을 배정하지 않는다.
                explain(day, observation, candidates, allocations.stream().mapToLong(MatchedReceipt::quantity).sum(), issues);
            }
            matched.addAll(allocations(event.id()));
        }
        receipts.flush();
        save(upload.getId(), new Report(List.copyOf(matched), new ReceiptMatching(
                issues.isEmpty() ? "EVALUATED" : "REVIEW_REQUIRED", List.copyOf(issues))));
    }

    private Map<Day, Observation> observations(CleaningResult result, Map<String, Product> products) {
        Map<Lot, Long> productByLot = new HashMap<>();
        result.inventoryUnits().forEach(u -> productByLot.put(new Lot(u.sourceInventoryKey(), u.receivedOn()),
                products.get(u.skuBarcode()).getId()));
        Map<Day, Long> lots = new HashMap<>();
        var excluded = new java.util.HashSet<Long>();
        result.validation().excludedSkus().forEach(e -> excluded.add(products.get(e.skuBarcode()).getId()));
        for (var row : result.unitDailyRecords()) {
            Day day = new Day(productByLot.get(new Lot(row.sourceInventoryKey(), row.receivedOn())), row.stockDate());
            lots.merge(day, row.inboundQty(), Math::addExact);
            if (row.openingQty() + row.inboundQty() - row.outboundQty() + row.adjustmentQty() != row.closingQty())
                excluded.add(day.productId());
        }
        Map<Day, Observation> observed = new LinkedHashMap<>();
        for (var row : result.productDailyStocks()) {
            Day day = new Day(products.get(row.skuBarcode()).getId(), row.stockDate());
            // isValidObservation은 수요 관측(시작 재고>0) 지표다. 첫 입고의 물리적 검산과 분리한다.
            boolean valid = !excluded.contains(day.productId())
                    && lots.getOrDefault(day, 0L).longValue() == row.inboundQty().longValue()
                    && row.openingQty() + row.inboundQty() - row.outboundQty() + row.adjustmentQty() == row.closingQty();
            observed.put(day, new Observation(row.inboundQty(), valid));
        }
        return observed;
    }

    private Event create(UploadFile upload, long userId, Day day, Observation observation) {
        jdbc.update("""
                INSERT INTO receipt_matching_events(user_id,warehouse_id,product_id,stock_date,quantity,valid_observation,source_upload_file_id)
                VALUES (?,?,?,?,?,?,?)
                """, userId, upload.getWarehouseId(), day.productId(), day.date(), observation.quantity(), observation.valid(), upload.getId());
        long id = jdbc.queryForObject("SELECT id FROM receipt_matching_events WHERE warehouse_id = ? AND product_id = ? AND stock_date = ?",
                Long.class, upload.getWarehouseId(), day.productId(), day.date());
        return new Event(id, day, observation.quantity(), observation.valid(), false);
    }

    private void allocate(UploadFile upload, long eventId, Day day, Observation observation, List<PlannedReceipt> candidates,
            Map<Long, String> names, List<ReceiptMatchingIssue> issues) {
        long remaining = observation.quantity();
        if (observation.valid()) {
            for (var receipt : candidates) {
                if (!eligible(receipt, day)) continue;
                if (remaining < receipt.getQuantity()) break;
                jdbc.update("""
                        INSERT INTO receipt_matching_allocations(receipt_id,event_id,warehouse_id,product_id,quantity,sku_name)
                        VALUES (?,?,?,?,?,?)
                        """, receipt.getId(), eventId, upload.getWarehouseId(), day.productId(), receipt.getQuantity(), names.get(day.productId()));
                receipt.receive(day.date());
                remaining -= receipt.getQuantity();
            }
        }
        explain(day, observation, candidates, observation.quantity() - remaining, issues);
    }

    private static boolean eligible(PlannedReceipt receipt, Day day) {
        return receipt.getProductId() == day.productId() && receipt.isMatchable()
                && receipt.getCreatedAt().atZone(BUSINESS_ZONE).toLocalDate().isBefore(day.date());
    }

    private void explain(Day day, Observation observation, List<PlannedReceipt> candidates, long used,
            List<ReceiptMatchingIssue> issues) {
        List<PlannedReceipt> relevant = candidates.stream().filter(r -> r.getProductId() == day.productId()
                && !r.isReceived() && !r.isCancelled()
                && !r.getCreatedAt().atZone(BUSINESS_ZONE).toLocalDate().isAfter(day.date())).toList();
        if (relevant.isEmpty()) return;
        if (!observation.valid()) issues.add(new ReceiptMatchingIssue(day.productId(), day.date(), "INVALID_OBSERVATION"));
        if (observation.quantity() == 0) return;
        if (relevant.stream().anyMatch(r -> r.getCreatedAt().atZone(BUSINESS_ZONE).toLocalDate().equals(day.date())))
            issues.add(new ReceiptMatchingIssue(day.productId(), day.date(), "REGISTRATION_DAY"));
        if (relevant.stream().anyMatch(r -> r.getExpectedOn() == null))
            issues.add(new ReceiptMatchingIssue(day.productId(), day.date(), "NO_EXPECTED_DATE"));
        var first = relevant.stream().filter(r -> eligible(r, day)).findFirst();
        if (observation.valid() && first.isPresent())
            issues.add(new ReceiptMatchingIssue(day.productId(), day.date(),
                    observation.quantity() - used < first.get().getQuantity() ? "INSUFFICIENT_QUANTITY" : "PREVIOUSLY_EVALUATED"));
    }

    private List<MatchedReceipt> allocations(long eventId) {
        return jdbc.query("""
                SELECT a.receipt_id,a.sku_name,a.quantity,e.stock_date FROM receipt_matching_allocations a
                JOIN receipt_matching_events e ON e.id = a.event_id WHERE a.event_id = ? ORDER BY a.receipt_id
                """, (rs, n) -> new MatchedReceipt(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getObject(4, LocalDate.class), "RECEIVED"), eventId);
    }

    private void save(long uploadId, Report report) {
        jdbc.update("INSERT INTO receipt_matching_reports(upload_file_id,report) VALUES (?,CAST(? AS JSONB))",
                uploadId, mapper.writeValueAsString(report));
    }

    @Transactional(readOnly = true)
    public Report report(long uploadId) {
        var reports = jdbc.queryForList("SELECT report FROM receipt_matching_reports WHERE upload_file_id = ?", String.class, uploadId);
        return reports.isEmpty() ? Report.empty("NOT_EVALUATED")
                : mapper.treeToValue(EngineJobs.readDatabaseJson(mapper, reports.getFirst()), Report.class);
    }
}
