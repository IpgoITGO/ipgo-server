package com.ipgo.server.service;

import com.ipgo.server.dto.response.ScenarioComparison;
import com.ipgo.server.entity.CalculationResult;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.util.DisplayQuantities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CustomScenarioStore {
    // scenario stock/demand columns are NUMERIC(18,3). Never truncate an overflowing request.
    private static final long MAX_QUANTITY = 999_999_999_999_999L;
    private static final BigDecimal MAX_NUMBER = new BigDecimal("999999999999999.999");
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final UploadFileRepository uploads;
    private final Clock clock;
    private final boolean enabled;

    public CustomScenarioStore(JdbcTemplate jdbc, JsonMapper mapper, UploadFileRepository uploads, Clock clock,
            @Value("${ipgo.engine.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.mapper = mapper; this.uploads = uploads; this.clock = clock; this.enabled = enabled;
    }

    public record Input(long userId, long resultId, long warehouseId, long runId, long qty, Long previousId,
            JsonNode request, BigDecimal preArrival, int horizon, LocalDate today, boolean riskAllowed,
            ScenarioComparison.Scenario cached) { }

    private record Result(long runId, long productId, long warehouseId, long userId, long productOwner,
            String status, String runStatus, LocalDate asOf, LocalDate start, LocalDate origin, int horizon,
            BigDecimal preArrival, boolean riskAllowed, boolean active, String snapshot) { }

    @Transactional(readOnly = true)
    public Input prepare(long userId, long resultId, long qty) { return load(userId, resultId, qty); }

    private Input load(long userId, long resultId, long qty) {
        List<Result> rows = jdbc.query("""
                SELECT r.run_id,r.product_id,cr.warehouse_id,w.user_id,p.user_id AS product_owner,
                    r.status,cr.status AS run_status,cr.as_of_date,r.start_date,r.origin_date,r.horizon_days,
                    r.pre_arrival_unmet_qty,r.pre_arrival_risk,r.accuracy_grade,p.is_active,cr.selection_snapshot
                FROM calculation_results r JOIN calculation_runs cr ON cr.id=r.run_id
                JOIN warehouses w ON w.id=cr.warehouse_id JOIN products p ON p.id=r.product_id WHERE r.id=?
                """, (rs, i) -> new Result(rs.getLong("run_id"),rs.getLong("product_id"),rs.getLong("warehouse_id"),
                        rs.getLong("user_id"),rs.getLong("product_owner"),rs.getString("status"),rs.getString("run_status"),
                        rs.getObject("as_of_date",LocalDate.class),rs.getObject("start_date",LocalDate.class),
                        rs.getObject("origin_date",LocalDate.class),rs.getInt("horizon_days"),
                        rs.getBigDecimal("pre_arrival_unmet_qty"),rs.getBigDecimal("pre_arrival_risk") != null
                        && !"LOW".equals(rs.getString("accuracy_grade")),rs.getBoolean("is_active"),
                        rs.getString("selection_snapshot")), resultId);
        if (rows.isEmpty()) throw ProductException.notFound();
        Result r = rows.getFirst();
        if (r.userId != userId || r.productOwner != userId) throw ProductException.forbidden();
        if (!enabled) throw ProductException.engineUnavailable();
        if (qty < 1 || qty > MAX_QUANTITY) throw ProductException.invalidInput();
        LocalDate now = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if (!r.active || !"DONE".equals(r.runStatus)
                || !(CalculationResult.OK.equals(r.status) || CalculationResult.NOT_NEEDED.equals(r.status))
                || r.asOf.isAfter(now) || NeedJudgment.daysSince(r.asOf, now) > NeedJudgment.MAX_DAYS_SINCE_AS_OF
                || !Objects.equals(jdbc.queryForObject("SELECT MAX(id) FROM calculation_runs WHERE warehouse_id=?",
                        Long.class, r.warehouseId),r.runId)) throw ProductException.scenarioConflict();
        ObjectNode request;
        long unit;
        try {
            JsonNode original = decode(r.snapshot).get("request");
            require(original != null && original.isObject());
            require(integer(original.get("userId")) == userId && integer(original.get("warehouseId")) == r.warehouseId
                    && integer(original.get("runId")) == r.runId);
            require(LocalDate.parse(original.path("asOfDate").asText()).equals(r.asOf) && r.origin.equals(r.asOf));
            require(LocalDate.parse(original.path("today").asText()).equals(r.start) && !r.start.isBefore(r.asOf));
            require(original.path("policy").isObject() && original.path("settings").isArray());
            JsonNode target = null;
            for (JsonNode settings : original.path("settings")) {
                if (integer(settings.get("productId")) == r.productId) { require(target == null); target = settings; }
            }
            require(target != null && integer(target.get("unitCost")) >= 0);
            JsonNode selected = original.get("productIds");
            if (selected != null && !selected.isNull()) {
                require(selected.isArray());
                boolean contains = false;
                for (JsonNode id : selected) if (integer(id) == r.productId) contains = true;
                require(contains);
            }
            unit = integer(target.get("orderUnitQty"));
            long lead = integer(target.get("leadTimeDays")), cycle = integer(target.get("reviewCycleDays"));
            require(unit > 0 && unit <= MAX_QUANTITY && lead > 0 && cycle > 0 && lead <= 366 && cycle <= 366
                    && lead + cycle == r.horizon && r.horizon <= 366);
            require(original.path("uploadFileIds").isArray() && !original.path("uploadFileIds").isEmpty());
            Set<Long> ids = new HashSet<>();
            for (JsonNode id : original.path("uploadFileIds")) require(integer(id) > 0 && ids.add(integer(id)));
            require(ids.equals(new HashSet<>(jdbc.queryForList("SELECT upload_file_id FROM run_source_files WHERE run_id=?",
                    Long.class,r.runId))));
            var latest = uploads.findLatestPerPeriod(r.warehouseId);
            require(latest.stream().allMatch(f -> "DONE".equals(f.getStatus())));
            require(ids.equals(new HashSet<>(latest.stream().map(f -> f.getId()).toList())));
            request = (ObjectNode) original.deepCopy();
            // Preserve the full settings/source set for the same pooled risk sample; select one output SKU.
            request.set("productIds", mapper.valueToTree(List.of(r.productId)));
        } catch (RuntimeException e) { throw ProductException.scenarioConflict(); }
        long rounded;
        try { rounded = Math.multiplyExact((qty - 1) / unit + 1, unit); }
        catch (ArithmeticException e) { throw ProductException.invalidInput(); }
        if (rounded > MAX_QUANTITY) throw ProductException.invalidInput();
        var previous = custom(resultId,r.preArrival);
        return new Input(userId,resultId,r.warehouseId,r.runId,rounded,previous == null ? null : previous.scenarioId(),
                request,r.preArrival,r.horizon,r.start,r.riskAllowed,
                previous != null && previous.qty() == rounded ? previous : null);
    }

    @Transactional
    public ScenarioComparison.Scenario save(Input input, JsonNode response) {
        validate(response,input);
        // Same lock order as calculation updates. Lock only after HTTP has completed.
        if (jdbc.queryForList("SELECT id FROM warehouses WHERE id=? AND user_id=? FOR UPDATE",Long.class,
                input.warehouseId,input.userId).isEmpty()) throw ProductException.notFound();
        if (jdbc.queryForList("SELECT id FROM calculation_results WHERE id=? FOR UPDATE",Long.class,
                input.resultId).isEmpty()) throw ProductException.notFound();
        Input current = load(input.userId,input.resultId,input.qty);
        if (input.runId != current.runId || !input.request.equals(current.request)) throw ProductException.scenarioConflict();
        if (current.cached != null) return current.cached;
        if (!Objects.equals(input.previousId,current.previousId)) throw ProductException.scenarioChanged();
        boolean duplicate = jdbc.queryForObject("""
                SELECT COUNT(*) FROM scenario_results WHERE result_id=? AND scenario_type IN ('P80','P100','P120')
                AND scenario_qty=?
                """, Integer.class,input.resultId,input.qty) > 0;
        jdbc.update("DELETE FROM scenario_results WHERE result_id=? AND scenario_type='CUSTOM'",input.resultId);
        jdbc.update("""
                INSERT INTO scenario_results(result_id,scenario_type,scenario_qty,is_duplicate,expected_depletion_on,
                    expected_ending_stock,unmet_demand_qty,post_arrival_risk,daily_projection)
                VALUES (?,'CUSTOM',?,?,?,?,?,?,CAST(? AS JSONB))
                """,input.resultId,input.qty,duplicate,date(response.get("expectedDepletionOn")),
                number(response.get("expectedEndingStock"),false).setScale(3,RoundingMode.HALF_UP),
                number(response.get("unmetDemandQty"),false).setScale(3,RoundingMode.HALF_UP),
                response.path("postArrivalRisk").isNull() ? null
                        : number(response.get("postArrivalRisk"),false).setScale(4,RoundingMode.HALF_UP),
                mapper.writeValueAsString(response.get("dailyProjection")));
        return custom(input.resultId,input.preArrival);
    }

    private void validate(JsonNode response, Input input) {
        try {
            require(response.isObject() && "CUSTOM".equals(response.path("type").asText())
                    && integer(response.get("qty")) == input.qty && response.path("isDuplicate").isBoolean());
            number(response.get("expectedEndingStock"),false);
            BigDecimal unmet = number(response.get("unmetDemandQty"),false);
            require(input.preArrival == null || unmet.setScale(3,RoundingMode.HALF_UP).compareTo(input.preArrival) >= 0);
            JsonNode risk = response.get("postArrivalRisk");
            require(risk != null && (risk.isNull() || input.riskAllowed));
            if (!risk.isNull()) require(number(risk,false).compareTo(BigDecimal.ONE) <= 0);
            LocalDate depletion = date(response.get("expectedDepletionOn"));
            require(depletion == null || depletion.isAfter(input.today) && !depletion.isAfter(input.today.plusDays(input.horizon)));
            JsonNode projection = response.get("dailyProjection");
            require(projection != null && projection.isArray() && projection.size() <= input.horizon);
            LocalDate last = input.today;
            for (JsonNode day : projection) {
                LocalDate d = date(day.get("date"));
                require(d != null && d.isAfter(last) && !d.isAfter(input.today.plusDays(input.horizon)));
                number(day.get("closingQty"),false);
                last = d;
            }
        } catch (RuntimeException e) { throw ProductException.engineFailure(); }
    }

    private ScenarioComparison.Scenario custom(long resultId, BigDecimal preArrival) {
        var rows = jdbc.query("""
                SELECT id,scenario_qty,is_duplicate,expected_depletion_on,expected_ending_stock,unmet_demand_qty,
                    post_arrival_risk FROM scenario_results WHERE result_id=? AND scenario_type='CUSTOM'
                """,(rs,i) -> {
                    BigDecimal total = rs.getBigDecimal("unmet_demand_qty");
                    return new ScenarioComparison.Scenario(rs.getLong("id"),"CUSTOM",rs.getLong("scenario_qty"),
                            rs.getBoolean("is_duplicate"),rs.getObject("expected_depletion_on",LocalDate.class),
                            DisplayQuantities.stock(rs.getBigDecimal("expected_ending_stock")),DisplayQuantities.demand(total),
                            preArrival == null ? null : DisplayQuantities.demand(total.subtract(preArrival).max(BigDecimal.ZERO)),
                            rs.getBigDecimal("post_arrival_risk"));
                },resultId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private JsonNode decode(String value) {
        try {
            JsonNode node = mapper.readTree(value);
            return node.isTextual() ? mapper.readTree(node.asText()) : node;
        } catch (RuntimeException e) { throw ProductException.scenarioConflict(); }
    }
    private static long integer(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong());
        return node.longValue();
    }
    private static BigDecimal number(JsonNode node, boolean nullable) {
        if (nullable && (node == null || node.isNull())) return null;
        require(node != null && node.isNumber());
        BigDecimal value = node.decimalValue();
        require(value.signum() >= 0 && value.compareTo(MAX_NUMBER) <= 0);
        return value;
    }
    private static LocalDate date(JsonNode node) {
        require(node != null);
        if (node.isNull()) return null;
        require(node.isTextual());
        return LocalDate.parse(node.asText());
    }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid scenario contract");
    }
}
