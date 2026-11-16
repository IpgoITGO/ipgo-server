package com.ipgo.server.service;

import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.repository.WarehouseRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 정제(CLEAN) 요청을 언제 보낼지 정한다(#230).
 *
 * <p>AI는 처리하는 순간 "직전 달 최신 revision이 DONE이면 그 id, 아니면 null"을 다시 보고 요청 값과 비교한다.
 * 그래서 직전 달 최신 업로드가 아직 PROCESSING이면 다음 달 요청을 보내지 않고 기다렸다가,
 * 직전 달이 DONE이나 FAILED로 끝난 뒤 그 순간의 값으로 previousUploadFileId를 정해 보낸다.
 * 직전 달이 FAILED로 끝나면 직전 달이 없을 때처럼 null로 보낸다.
 *
 * <p>"기다리는 중"은 따로 저장하지 않는다. PROCESSING인데 engine_jobs에 그 업로드의 jobId 행이 없으면 기다리는 중이다.
 * 모든 메서드는 호출한 쪽이 같은 트랜잭션에서 창고를 먼저 잠근 상태에서 부른다(업로드·결과 수신과 같은 잠금 순서).
 */
@Service
public class UploadCleaningRequests {
    // AI(ipgo-ai app/repository.py)가 직전 달 값이 다를 때 보내는 문장이다. 이 문장은 따로 구분 코드가 없다.
    static final String PREVIOUS_MISMATCH_MESSAGE = "직전 월 최신 업로드가 요청과 일치하지 않아요";
    // 처음 요청 1번 + 직전 달 불일치 자동 재요청 1번.
    static final int MAX_CLEAN_REQUESTS = 2;

    private final UploadFileRepository uploads;
    private final WarehouseRepository warehouses;
    private final EngineJobs jobs;
    private final JdbcTemplate jdbc;

    public UploadCleaningRequests(UploadFileRepository uploads, WarehouseRepository warehouses, EngineJobs jobs,
            JdbcTemplate jdbc) {
        this.uploads = uploads;
        this.warehouses = warehouses;
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    /** 이 기간에서 기다리는 업로드를, 직전 달이 끝났으면 지금 보낸다. 직전 달이 아직 처리 중이면 그대로 둔다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void dispatchReady(long warehouseId, LocalDate periodStart) {
        if (!jobs.enabled()) return;
        uploads.flush(); // 같은 트랜잭션에서 바꾼 상태·jobId를 아래 JDBC 조회가 보도록 먼저 반영한다.
        Optional<UploadFile> previous = latestOf(warehouseId, periodStart.minusMonths(1));
        if (previous.filter(UploadFile::isProcessing).isPresent()) {
            // 직전 달이 끝나면 previousFinished가 다시 부른다. 직전 달도 아직 요청 전이면(예: 엔진이 꺼져 있던 때 남은 행)
            // 그 달부터 보낸다. 그래야 이 달이 끝나지 않는 직전 달을 영원히 기다리지 않는다.
            if (!requested(previous.get())) dispatchReady(warehouseId, previous.get().getPeriodStart());
            return;
        }
        Long previousId = previous.filter(UploadCleaningRequests::isDone).map(UploadFile::getId).orElse(null);
        List<Long> waiting = jdbc.queryForList("""
                SELECT uf.id FROM upload_files uf
                WHERE uf.warehouse_id = ? AND uf.period_start = ? AND uf.status = 'PROCESSING'
                  AND NOT EXISTS (SELECT 1 FROM engine_jobs j WHERE j.job_id = uf.job_id)
                """, Long.class, warehouseId, periodStart);
        if (waiting.isEmpty()) return;
        long userId = warehouses.findById(warehouseId).orElseThrow().getUserId();
        uploads.findAllById(waiting).stream().sorted(Comparator.comparing(UploadFile::getRevisionNo))
                .forEach(upload -> enqueue(upload, userId, previousId));
    }

    /** 한 달의 정제가 끝났으면(DONE·FAILED) 다음 달에서 기다리던 업로드를 보낸다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void previousFinished(long uploadId) {
        UploadFile finished = uploads.findById(uploadId).orElseThrow();
        dispatchReady(finished.getWarehouseId(), finished.getPeriodStart().plusMonths(1));
    }

    /**
     * 직전 달 값이 달라 실패한 첫 요청이면 새 jobId로 한 번 더 요청한다. 다시 요청했으면 true.
     * 직전 달이 그사이 다시 처리 중이 되었으면 보내지 않고 기다렸다가 직전 달이 끝난 뒤 보낸다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retryPreviousMismatch(EngineJobs.Job job, JsonNode error) {
        if (!jobs.enabled() || !"INVALID_SOURCE".equals(error.path("code").asText())) return false;
        UploadFile upload = uploads.findByIdForUpdate(job.targetId()).orElseThrow();
        if (!upload.isProcessing() || !job.id().equals(upload.getJobId())) return false;
        // AI 문장이 바뀌어도 알아채도록, 보낸 값과 지금 기준 값이 다른지도 함께 본다.
        boolean mismatch = PREVIOUS_MISMATCH_MESSAGE.equals(error.path("message").asText("").strip())
                || !Objects.equals(sentPrevious(job), expectedPrevious(upload));
        if (!mismatch || requestCount(upload.getId()) >= MAX_CLEAN_REQUESTS) return false;
        upload.restartCleaning();
        dispatchReady(upload.getWarehouseId(), upload.getPeriodStart());
        return true;
    }

    private void enqueue(UploadFile upload, long userId, Long previousId) {
        if (upload.getJobId() == null) upload.restartCleaning(); // job_id가 비어 있는 옛 행도 새 jobId로 보낸다.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uploadId", upload.getId());
        payload.put("userId", userId);
        payload.put("warehouseId", upload.getWarehouseId());
        payload.put("previousUploadFileId", previousId);
        payload.put("files", List.of(Map.of("uploadFileId", upload.getId(), "storageKey", upload.getStorageKey(),
                "originalName", upload.getOriginalName())));
        jobs.enqueue(upload.getJobId(), "CLEAN", upload.getId(), upload.getWarehouseId(), payload);
    }

    // AI와 같은 기준: 직전 달 최신 revision이 DONE이면 그 id, 처리 중·실패·없음이면 null.
    private Long expectedPrevious(UploadFile upload) {
        return latestOf(upload.getWarehouseId(), upload.getPeriodStart().minusMonths(1))
                .filter(UploadCleaningRequests::isDone).map(UploadFile::getId).orElse(null);
    }

    private static Long sentPrevious(EngineJobs.Job job) {
        JsonNode value = job.payload().path("previousUploadFileId");
        return value.isIntegralNumber() ? value.asLong() : null;
    }

    private Optional<UploadFile> latestOf(long warehouseId, LocalDate periodStart) {
        return uploads.findLatestPerPeriod(warehouseId).stream()
                .filter(u -> u.getPeriodStart().equals(periodStart)).findFirst();
    }

    private boolean requested(UploadFile upload) {
        return upload.getJobId() != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM engine_jobs WHERE job_id = ?", Integer.class, upload.getJobId()) > 0;
    }

    private int requestCount(long uploadId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM engine_jobs WHERE job_type = 'CLEAN' AND target_id = ?",
                Integer.class, uploadId);
    }

    private static boolean isDone(UploadFile upload) { return "DONE".equals(upload.getStatus()); }
}
