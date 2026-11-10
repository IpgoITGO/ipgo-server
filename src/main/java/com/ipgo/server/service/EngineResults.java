package com.ipgo.server.service;

import com.ipgo.server.dto.CleaningResult;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.repository.WarehouseRepository;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The caller may ACK only after this transactional proxy returns successfully. */
@Service
public class EngineResults {
    private final EngineJobs jobs;
    private final JdbcTemplate jdbc;
    private final WarehouseRepository warehouses;
    private final UploadFileRepository uploads;
    private final UploadCleaningService cleaning;
    private final EngineCalculationService calculations;
    private final EngineResultWriter writer;
    private final JsonMapper mapper;

    public EngineResults(EngineJobs jobs, JdbcTemplate jdbc, WarehouseRepository warehouses, UploadFileRepository uploads,
            UploadCleaningService cleaning, EngineCalculationService calculations, EngineResultWriter writer, JsonMapper mapper) {
        this.jobs = jobs; this.jdbc = jdbc; this.warehouses = warehouses; this.uploads = uploads;
        this.cleaning = cleaning; this.calculations = calculations; this.writer = writer; this.mapper = mapper;
    }

    @Transactional
    public void accept(String body) {
        JsonNode message = EngineResultTransport.decode(body, mapper);
        require(message.isObject() && "1".equals(message.path("contractVersion").asText()));
        require(message.path("jobId").isString());
        EngineJobs.Job job = lock(message.get("jobId").asText());
        require(job.type().equals(message.path("jobType").asText()));
        String status = message.path("status").asText();
        require(Set.of("SUCCEEDED", "FAILED").contains(status));
        if (Set.of("DONE","FAILED").contains(job.status())) return;
        boolean success = "SUCCEEDED".equals(status);
        if (success) require(message.hasNonNull("result") && !message.hasNonNull("error"));
        else require(message.hasNonNull("error") && !message.hasNonNull("result"));
        if ("CLEAN".equals(job.type())) {
            if (success) {
                cleaning.complete(job.targetId(), job.id(), mapper.treeToValue(message.get("result"), CleaningResult.class));
                // A late result for an overwritten revision is stored historically but does not start a stale calculation.
                var upload = uploads.findById(job.targetId()).orElseThrow();
                boolean current = uploads.findLatestPerPeriod(job.warehouseId()).stream().anyMatch(u -> u.getId().equals(upload.getId()));
                if (current) calculations.request(warehouses.findById(job.warehouseId()).orElseThrow().getUserId(),
                        job.warehouseId(), "UPLOAD", false);
            } else {
                JsonNode error = message.path("error");
                String code = error.path("code").asText();
                if (Set.of("INVALID_SOURCE","INVALID_INPUT","RESULT_TOO_LARGE").contains(code)) {
                    // 셀러가 고칠 수 있는 입력 문제만 엔진 문장을 남긴다. 엔진 장애 문구는 저장하지 않는다(#164).
                    cleaning.fail(job.targetId(), job.id(), UploadCleaningService.Failure.INVALID_SOURCE,
                            sellerReason(error.path("message")), detailReason(error.path("details").path("reason")));
                } else {
                    cleaning.fail(job.targetId(), job.id(), UploadCleaningService.Failure.ENGINE_UNAVAILABLE);
                }
            }
        } else {
            if (success) writer.complete(job, message.get("result")); else writer.fail(job);
        }
        jobs.finished(job.id(), success);
    }

    @Transactional
    public void expire(String id) {
        EngineJobs.Job job = lock(id);
        if (Set.of("DONE","FAILED").contains(job.status())) return;
        if ("CLEAN".equals(job.type())) cleaning.fail(job.targetId(), job.id(), UploadCleaningService.Failure.ENGINE_UNAVAILABLE);
        else writer.fail(job, EngineResultWriter.TIMEOUT_MESSAGE);
        jobs.finished(id, false);
    }

    private EngineJobs.Job lock(String id) {
        EngineJobs.Job initial = jobs.find(id);
        // Same warehouse -> job -> upload/run lock order as domain writers.
        warehouses.findByIdForUpdate(initial.warehouseId()).orElseThrow();
        jdbc.queryForObject("SELECT job_id FROM engine_jobs WHERE job_id = ? FOR UPDATE", String.class, id);
        return jobs.find(id);
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid engine envelope"); }

    static final int MAX_REASON_LENGTH = 300;

    // 화면에 그대로 보여 줄 문장이라 줄바꿈 · 제어 문자를 공백으로 바꾸고 길이를 제한한다. 비어 있으면 null.
    static String sellerReason(JsonNode node) {
        if (node == null || !node.isString()) return null;
        String text = node.asText().replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) return null;
        return text.length() > MAX_REASON_LENGTH ? text.substring(0, MAX_REASON_LENGTH) : text;
    }

    // 구분 코드는 영문 대문자 · 숫자 · 밑줄만 받는다(예: PREVIOUS_MONTH_MISMATCH). 그 밖은 버린다.
    static String detailReason(JsonNode node) {
        if (node == null || !node.isString()) return null;
        String text = node.asText();
        return text.matches("[A-Z][A-Z0-9_]{0,49}") ? text : null;
    }
}
