package com.ipgo.server.service;

import com.ipgo.server.dto.request.ReceiptCorrectionRequest;
import com.ipgo.server.dto.response.ReceiptCorrection.*;
import com.ipgo.server.entity.PlannedReceipt;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.PlannedReceiptRepository;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.repository.WarehouseRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 과거 U2는 snapshot, 이 service는 현재 배정의 사용자 정정 경계다. */
@Service
public class ReceiptCorrectionService {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final PlannedReceiptRepository receipts;
    private final UploadFileRepository uploads;
    private final WarehouseRepository warehouses;
    private final ReceiptCorrectionSources sources;
    private final EngineCalculationService calculations;
    private final EngineJobs jobs;
    private final BillingService billing;
    private final JsonMapper mapper;
    private final Clock clock;

    public ReceiptCorrectionService(JdbcTemplate jdbc,PlannedReceiptRepository receipts,UploadFileRepository uploads,
            WarehouseRepository warehouses,ReceiptCorrectionSources sources,EngineCalculationService calculations,
            EngineJobs jobs,BillingService billing,JsonMapper mapper,Clock clock) {
        this.jdbc=jdbc;this.receipts=receipts;this.uploads=uploads;this.warehouses=warehouses;this.sources=sources;
        this.calculations=calculations;this.jobs=jobs;this.billing=billing;this.mapper=mapper;this.clock=clock;
    }
    private record Event(long id,long owner,long warehouse,long product,LocalDate day,long quantity,boolean valid,
            long originalUpload,int originalRevision,boolean review,long sequence,String lastDecision) { }
    private record Context(Event event,Warehouse warehouse,Detail detail,List<PlannedReceipt> receipts,
            Map<Long,Long> allocatedTo,LocalDate today,LocalDate asOf) { }
    private record RequestHash(long reviewId,String version,Action action,List<Long> receipts,String reason) { }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(long userId,State state,long cursor,int size) {
        if(cursor<0 || size<1 || size>50) throw ProductException.invalidInput();
        String predicate=switch(state) {
            case OPEN -> "e.review_required=TRUE";
            case RESOLVED -> "e.review_required=FALSE AND e.last_decision_id IS NOT NULL";
            case ALL -> "(e.review_required=TRUE OR e.last_decision_id IS NOT NULL)";
        };
        var ids=jdbc.queryForList("SELECT e.id FROM receipt_matching_events e WHERE e.user_id=? AND e.id>? AND "+predicate+" ORDER BY e.id FETCH FIRST ? ROWS ONLY",Long.class,userId,cursor,size+1);
        boolean more=ids.size()>size;
        var selected=ids.stream().limit(size).toList();
        var details=selected.stream().map(id->context(ownedEvent(userId,id)).detail()).toList();
        return new Page(details,more?selected.getLast():null);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(long userId,long reviewId) { return context(ownedEvent(userId,reviewId)).detail(); }

    @Transactional
    public Preview preview(long userId,long reviewId,ReceiptCorrectionRequest.Preview request) {
        Context c=context(lock(userId,reviewId));
        return plan(c,request);
    }

    @Transactional
    public Saved decide(long userId,long reviewId,ReceiptCorrectionRequest.Decide request) {
        Event locked=lock(userId,reviewId);
        var ids=canonicalIds(request.receiptIds());
        if(request.reason()==null) throw ProductException.invalidInput();
        String reason=request.reason().strip();
        if(reason.isEmpty() || reason.length()>500 || request.idempotencyKey()==null) throw ProductException.invalidInput();
        String key=request.idempotencyKey().toString();
        String hash=ReceiptCorrectionSources.hash(mapper.writeValueAsString(new RequestHash(reviewId,request.expectedVersion(),request.action(),ids,reason)));
        var prior=jdbc.query("SELECT request_hash,response FROM receipt_correction_decisions WHERE user_id=? AND idempotency_key=?",
                (rs,n)->new String[]{rs.getString(1),rs.getString(2)},userId,key);
        if(!prior.isEmpty()) {
            if(!prior.getFirst()[0].equals(hash)) throw ProductException.correctionIdempotencyConflict();
            return mapper.treeToValue(EngineJobs.readDatabaseJson(mapper,prior.getFirst()[1]),Saved.class);
        }
        billing.assertWriteAllowed(userId);
        Context c=context(locked);
        Preview p=plan(c,request.preview());
        String decisionId=UUID.randomUUID().toString();
        Map<Long,PlannedReceipt> byId=new HashMap<>();c.receipts().forEach(r->byId.put(r.getId(),r));
        var beforeIds=p.before().stream().map(Allocation::receiptId).toList();
        var afterIds=p.after().stream().map(Allocation::receiptId).toList();
        var removed=p.before().stream().filter(a->!afterIds.contains(a.receiptId())).toList();
        var added=p.after().stream().filter(a->!beforeIds.contains(a.receiptId())).toList();
        for(var a:removed) {
            byId.get(a.receiptId()).releaseForCorrection(c.event().day());
            if(jdbc.update("DELETE FROM receipt_matching_allocations WHERE receipt_id=? AND event_id=?",a.receiptId(),reviewId)!=1)
                throw ProductException.correctionStale();
        }
        for(var a:added) {
            byId.get(a.receiptId()).receive(c.event().day());
            jdbc.update("INSERT INTO receipt_matching_allocations(receipt_id,event_id,warehouse_id,product_id,quantity,sku_name) VALUES (?,?,?,?,?,?)",
                    a.receiptId(),reviewId,c.event().warehouse(),c.event().product(),a.quantity(),a.skuName());
        }
        receipts.flush();
        jdbc.update("UPDATE receipt_matching_events SET review_required=FALSE,confirmed_source_fingerprint=?,correction_version=correction_version+1,last_decision_id=? WHERE id=?",
                c.detail().currentSource().fingerprint(),decisionId,reviewId);
        Long runId=removed.isEmpty() && added.isEmpty()?null:
                calculations.request(userId,c.event().warehouse(),"RECEIPTS",false,c.today());
        Saved result=new Saved(decisionId,"RESOLVED",context(event(reviewId)).detail().version(),runId);
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("before",c.detail());snapshot.put("preview",p);snapshot.put("after",p.after());
        snapshot.put("source",c.detail().currentSource());snapshot.put("actorUserId",userId);
        snapshot.put("decidedAt",clock.instant().toString());
        try {
            jdbc.update("""
                    INSERT INTO receipt_correction_decisions(id,event_id,user_id,warehouse_id,product_id,source_upload_file_id,
                        source_fingerprint,action,reason,idempotency_key,request_hash,previous_version,snapshot,response)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSONB),CAST(? AS JSONB))
                    """,decisionId,reviewId,userId,c.event().warehouse(),c.event().product(),c.detail().currentSource().uploadId(),
                    c.detail().currentSource().fingerprint(),request.action().name(),reason,key,hash,request.expectedVersion(),
                    mapper.writeValueAsString(snapshot),mapper.writeValueAsString(result));
        } catch(DuplicateKeyException conflict) {
            // 다른 창고에서 같은 사용자 키를 동시에 사용해도 모든 projection/outbox를 rollback한다.
            throw ProductException.correctionIdempotencyConflict();
        }
        for(var a:removed) history(c,a,"RELEASE",decisionId);
        for(var a:added) history(c,a,"ASSIGN",decisionId);
        return result;
    }

    private Preview plan(Context c,ReceiptCorrectionRequest.Preview request) {
        var ids=canonicalIds(request.receiptIds());
        if(request.action()==null || request.expectedVersion()==null) throw ProductException.invalidInput();
        if(!c.detail().version().equals(request.expectedVersion())) throw ProductException.correctionStale();
        if(!c.event().review() || !c.detail().sourceReady()) throw ProductException.correctionUnavailable();
        List<Allocation> after;
        if(request.action()==Action.KEEP) {
            if(!ids.isEmpty()) throw ProductException.invalidInput();
            after=c.detail().allocations();
        } else {
            if(!ids.isEmpty() && !c.detail().capabilities().replace()) throw ProductException.invalidInput();
            var byId=new HashMap<Long,PlannedReceipt>();c.receipts().forEach(r->byId.put(r.getId(),r));
            var old=new HashMap<Long,Allocation>();c.detail().allocations().forEach(a->old.put(a.receiptId(),a));
            var selected=new ArrayList<Allocation>();long total=0;
            for(long id:ids) {
                PlannedReceipt r=byId.get(id);
                if(r==null || !eligible(r,c)) throw ProductException.invalidInput();
                if(r.getQuantity()>c.detail().currentSource().quantity()-total) throw ProductException.invalidInput();
                total+=r.getQuantity();
                selected.add(old.getOrDefault(id,new Allocation(id,r.getQuantity(),c.detail().skuName(),c.event().day())));
            }
            after=List.copyOf(selected);
        }
        var beforeIds=c.detail().allocations().stream().map(Allocation::receiptId).toList();
        var afterIds=after.stream().map(Allocation::receiptId).toList();
        var changes=c.receipts().stream().filter(r->beforeIds.contains(r.getId())!=afterIds.contains(r.getId())).map(r->{
            boolean received=afterIds.contains(r.getId());
            String next=received?"RECEIVED":r.getExpectedOn()==null?"NO_DATE":r.getExpectedOn().isBefore(c.today())?"OVERDUE":"PENDING";
            boolean future=r.getExpectedOn()!=null && c.asOf()!=null && r.getExpectedOn().isAfter(c.asOf());
            return new Change(r.getId(),r.statusOn(c.today()),next,r.isReflectedOn(c.today()) && future,!received && "PENDING".equals(next) && future);
        }).toList();
        return new Preview(c.detail().version(),request.action().name(),c.detail().allocations(),after,changes,c.detail().warnings(),
                !changes.isEmpty() && jobs.enabled() && c.warehouse().hasConfirmedDefaults());
    }

    private List<Long> canonicalIds(List<Long> ids) {
        if(ids==null || ids.size()>200 || ids.stream().anyMatch(id->id==null || id<=0) || new HashSet<>(ids).size()!=ids.size())
            throw ProductException.invalidInput();
        return ids.stream().sorted().toList();
    }
    private boolean eligible(PlannedReceipt r,Context c) {
        Long allocated=c.allocatedTo().get(r.getId());
        return (allocated==null?r.isMatchable():allocated==c.event().id() && r.isReceived())
                && r.getExpectedOn()!=null && r.getCreatedAt().atZone(SEOUL).toLocalDate().isBefore(c.event().day());
    }
    private void history(Context c,Allocation a,String action,String decision) {
        jdbc.update("""
                INSERT INTO receipt_allocation_history(event_id,receipt_id,warehouse_id,product_id,quantity,sku_name,received_on,action,decision_id)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,c.event().id(),a.receiptId(),c.event().warehouse(),c.event().product(),a.quantity(),a.skuName(),a.receivedOn(),action,decision);
    }
    private Event event(long id) {
        var found=jdbc.query("""
                SELECT e.id,e.user_id,e.warehouse_id,e.product_id,e.stock_date,e.quantity,e.valid_observation,
                    e.source_upload_file_id,u.revision_no,e.review_required,e.correction_version,e.last_decision_id
                FROM receipt_matching_events e JOIN upload_files u ON u.id=e.source_upload_file_id WHERE e.id=?
                """,(rs,n)->new Event(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getObject(5,LocalDate.class),
                rs.getLong(6),rs.getBoolean(7),rs.getLong(8),rs.getInt(9),rs.getBoolean(10),rs.getLong(11),rs.getString(12)),id);
        if(found.isEmpty()) throw ProductException.notFound();
        return found.getFirst();
    }
    private Event ownedEvent(long user,long id) {
        Event e=event(id);
        if(e.owner()!=user) throw ProductException.forbidden();
        if(!e.review() && e.lastDecision()==null) throw ProductException.notFound();
        return e;
    }
    private Event lock(long user,long id) {
        Event initial=event(id);
        if(initial.owner()!=user) throw ProductException.forbidden();
        Warehouse w=warehouses.findByIdForUpdate(initial.warehouse()).orElseThrow(ProductException::notFound);
        if(w.getUserId()!=user) throw ProductException.forbidden();
        return ownedEvent(user,id);
    }
    private Context context(Event e) {
        var w=warehouses.findById(e.warehouse()).orElseThrow(ProductException::notFound);
        var latest=uploads.findLatestPerPeriod(e.warehouse());
        UploadFile source=latest.stream().filter(u->u.getPeriodStart().equals(e.day().withDayOfMonth(1))).findFirst().orElseThrow(ProductException::correctionUnavailable);
        Source current=sources.read(source,e.product(),e.day());
        boolean ready="DONE".equals(source.getStatus());
        String name=jdbc.queryForObject("SELECT sku_name FROM products WHERE id=?",String.class,e.product());
        List<Allocation> allocations=jdbc.query("SELECT receipt_id,quantity,sku_name FROM receipt_matching_allocations WHERE event_id=? ORDER BY receipt_id",
                (rs,n)->new Allocation(rs.getLong(1),rs.getLong(2),rs.getString(3),e.day()),e.id());
        List<PlannedReceipt> active=receipts.findActive(e.warehouse()).stream().filter(r->r.getProductId()==e.product()).sorted(Comparator.comparing(PlannedReceipt::getId)).toList();
        Map<Long,Long> allocated=new TreeMap<>();
        jdbc.query("SELECT receipt_id,event_id FROM receipt_matching_allocations WHERE warehouse_id=? AND product_id=? ORDER BY receipt_id",
                rs->{allocated.put(rs.getLong(1),rs.getLong(2));},e.warehouse(),e.product());
        LocalDate today=LocalDate.now(clock.withZone(SEOUL));
        LocalDate asOf=latest.stream().filter(u->"DONE".equals(u.getStatus())).map(UploadFile::getPeriodEnd).max(LocalDate::compareTo).orElse(null);
        var versionInput=new LinkedHashMap<String,Object>();
        versionInput.put("event",e);versionInput.put("source",current);versionInput.put("sourceStatus",source.getStatus());
        versionInput.put("allocations",allocations);versionInput.put("allocatedTo",allocated);versionInput.put("today",today);
        versionInput.put("productName",name);
        versionInput.put("receipts",active.stream().map(r->Arrays.asList(r.getId(),r.getQuantity(),r.getExpectedOn(),r.getStatus(),r.getReceivedOn(),r.getCreatedAt(),r.getUpdatedAt())).toList());
        versionInput.put("uploads",latest.stream().map(u->Arrays.asList(u.getId(),u.getRevisionNo(),u.getStatus())).toList());
        versionInput.put("defaults",Arrays.asList(w.getDefaultLeadTimeDays(),w.getDefaultReviewCycleDays(),w.getDefaultOrderUnitQty()));
        String version=ReceiptCorrectionSources.hash(mapper.writeValueAsString(versionInput));
        var warnings=new ArrayList<String>();
        if(!ready) warnings.add("LATEST_SOURCE_NOT_READY");
        if(!current.present()) warnings.add("SOURCE_MISSING");
        if(!current.valid()) warnings.add("INVALID_OBSERVATION");
        if(allocations.stream().mapToLong(Allocation::quantity).reduce(0L,Math::addExact)>current.quantity()) warnings.add("INSUFFICIENT_QUANTITY");
        // transaction 시작 시각/UUID 정렬 대신 실제 마지막 확정 포인터를 우선한다.
        var decisions=jdbc.query("SELECT id,action,reason,user_id,created_at FROM receipt_correction_decisions WHERE event_id=? ORDER BY CASE WHEN id=? THEN 0 ELSE 1 END,created_at DESC,id DESC FETCH FIRST 20 ROWS ONLY",
                (rs,n)->new Decision(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getTimestamp(5).toInstant().toString()),e.id(),e.lastDecision());
        Decision lastDecision=decisions.stream().filter(d->d.decisionId().equals(e.lastDecision())).findFirst().orElse(null);
        Source original=new Source(e.originalUpload(),e.originalRevision(),true,e.quantity(),e.valid(),ReceiptCorrectionSources.fingerprint(true,e.quantity(),e.valid()));
        Capabilities capabilities=new Capabilities(ready && e.review(),ready && e.review() && current.present() && current.valid() && current.quantity()>0,ready && e.review());
        // 후보 판정은 위와 같은 event/창고/배정 집합을 사용한다.
        Context shell=new Context(e,w,null,active,allocated,today,asOf);
        var candidates=active.stream().map(r->new Candidate(r.getId(),r.getQuantity(),r.getExpectedOn(),r.statusOn(today),capabilities.replace() && eligible(r,shell))).toList();
        var detail=new Detail(e.id(),e.warehouse(),e.product(),name,e.day(),e.review()?"OPEN":"RESOLVED",version,ready,original,current,
                allocations,candidates,capabilities,List.copyOf(warnings),lastDecision,decisions);
        return new Context(e,w,detail,active,allocated,today,asOf);
    }
}
