package com.ipgo.server.service;

import com.ipgo.server.exception.ProductException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Five original rows are the shared lifetime grant, including calls made by the deployed old code. */
@Component
public class InsightBatchBudget {
    static final String IDS = java.util.stream.Stream.concat(InsightBatchManifest.ANCHORS.stream(),InsightBatchManifest.TARGETS.stream())
            .map(InsightBatchManifest.Target::resultId).distinct().sorted()
            .map(Object::toString).collect(java.util.stream.Collectors.joining(",","(",")"));
    private static final String DELEGATE = "batchBudgetDelegate";
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public InsightBatchBudget(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }

    record Row(long id,long product,long owner,long warehouseOwner,String mode,String status,String reason,String marker,
               boolean passed,String raw,String text,BigDecimal cost,Integer inputTokens,Integer outputTokens,JsonNode versions) { }
    record Snapshot(Map<Long,Row> rows,Map<Long,Long> delegatedTargets,List<Long> freeAnchors,int reserved,String gate) { }

    void lock() {
        // Same old-anchor order as the deployed version; new rows follow them. No HTTP under these locks.
        var locked=jdbc.queryForList("SELECT result_id FROM ai_explanations WHERE result_id IN "+IDS+" ORDER BY result_id FOR UPDATE",Long.class);
        if(locked.size()!=InsightBatchManifest.MAX_CALLS*2) throw ProductException.insightInputUnavailable();
    }

    Snapshot snapshot() {
        var rows=new HashMap<Long,Row>();
        jdbc.query("""
                SELECT a.*,r.product_id,p.user_id,w.user_id AS warehouse_owner FROM ai_explanations a
                JOIN calculation_results r ON r.id=a.result_id JOIN products p ON p.id=r.product_id
                JOIN calculation_runs cr ON cr.id=r.run_id JOIN warehouses w ON w.id=cr.warehouse_id
                WHERE a.result_id IN 
                """+IDS,(org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long id=rs.getLong("result_id");
            rows.put(id,new Row(id,rs.getLong("product_id"),rs.getLong("user_id"),rs.getLong("warehouse_owner"),
                    rs.getString("mode"),rs.getString("status"),rs.getString("reason_code"),rs.getString("output_path"),
                    rs.getBoolean("validation_passed"),rs.getString("raw_text"),rs.getString("rendered_text"),
                    rs.getBigDecimal("cost_usd"),(Integer)rs.getObject("input_tokens"),(Integer)rs.getObject("output_tokens"),
                    EngineJobs.readDatabaseJson(mapper,rs.getString("versions"))));
        });
        var delegated=new HashMap<Long,Long>();
        var free=new ArrayList<Long>();
        int used=0;String gate="OPEN";
        for(var a:InsightBatchManifest.ANCHORS) {
            Row old=rows.get(a.resultId());
            boolean reserved=old!=null && InsightBatchManifest.marker(a.resultId()).equals(old.marker());
            if(reserved) used++;
            String state;
            if(!owned(old,a)) state="INPUT_UNAVAILABLE";
            else if(unspent(old) && old.versions().isObject() && !old.versions().has(DELEGATE)) { free.add(a.resultId());state="OPEN"; }
            else if(reserved && old.versions().has(DELEGATE)) {
                long nextId=delegateTarget(old);
                Row next=rows.get(nextId);
                boolean fixed=InsightBatchManifest.TARGETS.stream().anyMatch(t -> t.resultId()==nextId);
                if(!fixed || !validDelegation(old,nextId) || next==null
                        || !InsightBatchManifest.marker(nextId).equals(next.marker())
                        || delegated.putIfAbsent(nextId,a.resultId())!=null) state="INPUT_UNAVAILABLE";
                else state=outcome(next);
            } else if(reserved) state=outcome(old);
            else state="INPUT_UNAVAILABLE";
            gate=combine(gate,state);
        }
        for(var t:InsightBatchManifest.TARGETS) {
            Row next=rows.get(t.resultId());
            if(!owned(next,t) || (delegated.containsKey(t.resultId()) ? unspent(next) : next!=null && !unspent(next)))
                gate="INPUT_UNAVAILABLE";
        }
        return new Snapshot(Map.copyOf(rows),Map.copyOf(delegated),List.copyOf(free),used,gate);
    }

    void requireUnused(Snapshot state,InsightBatchManifest.Target target) {
        if(state.gate().equals("INPUT_UNAVAILABLE")) throw ProductException.insightInputUnavailable();
        if(state.delegatedTargets().containsKey(target.resultId()) || state.freeAnchors().isEmpty())
            throw ProductException.insightSpent();
    }

    void delegate(Snapshot state,InsightBatchManifest.Target target) {
        long anchor=state.freeAnchors().getFirst(); // Shared pool, not one grant per SKU. Request cannot select an anchor.
        var versions=(ObjectNode)state.rows().get(anchor).versions().deepCopy();
        versions.putObject(DELEGATE).put("batchId",InsightBatchManifest.ID)
                .put("anchorResultId",anchor).put("resultId",target.resultId());
        // Old code sees its original marker + SKIPPED and refuses ALL further legacy calls; recovery ignores it.
        // Preserve reason/mode/text/tokens/cost/created_at so its public DTO is unchanged, not a fake LLM result.
        jdbc.update("""
                UPDATE ai_explanations SET status='SKIPPED',validation_passed=FALSE,output_path=?,versions=CAST(? AS JSONB)
                WHERE result_id=?
                """,InsightBatchManifest.marker(anchor),mapper.writeValueAsString(versions),anchor);
    }

    private static boolean owned(Row r,InsightBatchManifest.Target t) {
        return r!=null && r.product()==t.productId() && r.owner()==InsightBatchManifest.OWNER && r.warehouseOwner()==InsightBatchManifest.OWNER;
    }
    private static String combine(String a,String b) {
        if(a.equals("INPUT_UNAVAILABLE") || b.equals("INPUT_UNAVAILABLE")) return "INPUT_UNAVAILABLE";
        if(a.equals("STOPPED_AFTER_FAILURE") || b.equals("STOPPED_AFTER_FAILURE")) return "STOPPED_AFTER_FAILURE";
        if(a.equals("IN_PROGRESS") || b.equals("IN_PROGRESS")) return "IN_PROGRESS";
        return "OPEN";
    }
    private static long delegateTarget(Row r) {
        JsonNode tag=r.versions().path(DELEGATE);
        return tag.path("resultId").isIntegralNumber() ? tag.path("resultId").asLong() : -1;
    }
    private static boolean unspent(Row r) {
        return "NONE".equals(r.mode()) && "DISABLED".equals(r.status()) && "LLM_DISABLED".equals(r.reason())
                && r.marker()==null && noPaidFields(r);
    }
    private static boolean noPaidFields(Row r) {
        return r.raw()==null && r.text()==null && (r.cost()==null||r.cost().signum()==0) && r.inputTokens()==null && r.outputTokens()==null;
    }
    private static boolean validDelegation(Row r,long targetId) {
        JsonNode tag=r.versions().path(DELEGATE);
        return "SKIPPED".equals(r.status()) && "NONE".equals(r.mode()) && "LLM_DISABLED".equals(r.reason())
                && !r.passed() && noPaidFields(r) && tag.isObject() && InsightBatchManifest.ID.equals(tag.path("batchId").asText())
                && tag.path("anchorResultId").isIntegralNumber() && tag.path("anchorResultId").asLong()==r.id()
                && tag.path("resultId").isIntegralNumber() && tag.path("resultId").asLong()==targetId;
    }
    private static String outcome(Row r) {
        if("PROCESSING".equals(r.status())) return "IN_PROGRESS";
        if("DONE".equals(r.status()) && "LLM".equals(r.mode()) && r.passed()) return "OPEN";
        return "STOPPED_AFTER_FAILURE";
    }
}
