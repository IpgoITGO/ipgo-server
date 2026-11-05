package com.ipgo.server.service;

import com.ipgo.server.dto.StoredUpload;
import com.ipgo.server.dto.response.UploadAccepted;
import com.ipgo.server.dto.response.UploadPeriods;
import com.ipgo.server.dto.response.UploadResult;
import com.ipgo.server.repository.WarehouseRepository;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.exception.UploadException;
import com.ipgo.server.infrastructure.storage.UploadStorage;
import com.ipgo.server.repository.UploadFileRepository;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

// 업로드 순서: 파일 검사 → 파일명 기간 읽기 → 같은 기간 확인 → 원본 저장 → 업로드 기록 저장 (BE-03 · BE-04)
@Service
public class UploadRecordService {
    static final String SAME_PERIOD = "SAME_PERIOD";
    static final String SAME_FILE = "SAME_FILE";

    private final UploadStorage storage;
    private final WarehouseService warehouses;
    private final UploadFileRepository uploads;
    private final WarehouseRepository warehouseRepository;
    private final EngineJobs engineJobs;
    private final ReceiptMatchingService matching;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public UploadRecordService(UploadStorage storage, WarehouseService warehouses, UploadFileRepository uploads,
            WarehouseRepository warehouseRepository, EngineJobs engineJobs, org.springframework.jdbc.core.JdbcTemplate jdbc, ReceiptMatchingService matching) {
        this.storage = storage;
        this.warehouses = warehouses;
        this.uploads = uploads;
        this.warehouseRepository = warehouseRepository;
        this.engineJobs = engineJobs;
        this.jdbc = jdbc;
        this.matching = matching;
    }

    @Transactional
    public UploadAccepted upload(long userId, MultipartFile file, boolean overwrite) {
        storage.precheck(file);
        UploadPeriod period = UploadPeriod.fromFileName(file.getOriginalFilename());

        // 창고를 잠가서 같은 기간을 동시에 두 번 올려도 하나씩 확인한다.
        Warehouse warehouse = warehouses.lockDefault(userId);
        // 내용이 완전히 같은 파일을 다른 기간 이름으로 올리면 덮어쓰기와 상관없이 막는다(BE-04).
        // 같은 기간이면 아래 기간 확인에서 처리하므로, 같은 파일을 덮어써서 다시 정제할 수 있다.
        String hash = storage.contentHash(file);
        uploads.findFirstByWarehouseIdAndFileHashAndStatusNotOrderByIdDesc(warehouse.getId(), hash, UploadFile.FAILED)
                .filter(existing -> !existing.getPeriodStart().equals(period.start()))
                .ifPresent(existing -> { throw duplicate(existing, SAME_FILE, "이미 올린 파일입니다."); });
        if (!overwrite) {
            uploads.findFirstByWarehouseIdAndPeriodStartAndStatusNotOrderByRevisionNoDesc(
                    warehouse.getId(), period.start(), UploadFile.FAILED)
                    .ifPresent(existing -> { throw duplicate(existing, SAME_PERIOD, "이미 올린 기간입니다."); });
        }
        int revision = uploads.lastRevision(warehouse.getId(), period.start()) + 1;

        StoredUpload stored = storage.store(userId, file);
        if (!hash.equals(stored.sha256())) throw new IllegalStateException("Upload changed during validation");
        String key = ("DATABASE".equals(storage.kind()) ? "db/" : "") + userId + "/" + stored.uploadId();
        UploadFile saved = uploads.save(new UploadFile(warehouse.getId(), stored.originalName(),
                key, stored.sha256(), revision, period));
        storage.persistOriginal(saved, file);
        if (engineJobs.enabled()) {
            var previous = uploads.findLatestPerPeriod(warehouse.getId()).stream()
                    .filter(u -> u.getPeriodStart().equals(period.start().minusMonths(1)))
                    .filter(u -> "DONE".equals(u.getStatus())).findFirst();
            Map<String,Object> payload = new java.util.LinkedHashMap<>();
            payload.put("uploadId", saved.getId()); payload.put("userId", userId);
            payload.put("warehouseId", warehouse.getId());
            payload.put("previousUploadFileId", previous.map(UploadFile::getId).orElse(null));
            payload.put("files", List.of(Map.of("uploadFileId", saved.getId(), "storageKey", saved.getStorageKey(),
                    "originalName", saved.getOriginalName())));
            engineJobs.enqueue(saved.getJobId(), "CLEAN", saved.getId(), warehouse.getId(), payload);
        }
        return UploadAccepted.from(saved);
    }

    @Transactional(readOnly = true)
    public UploadPeriods periods(long userId) {
        return warehouses.findDefault(userId)
                .map(warehouse -> new UploadPeriods(
                        uploads.findLatestPerPeriod(warehouse.getId()).stream().map(UploadPeriods.Item::from).toList(),
                        uploads.findFirstByWarehouseIdOrderByIdDesc(warehouse.getId()).map(UploadFile::getId).orElse(null)))
                .orElse(new UploadPeriods(List.of(), null));
    }

    @Transactional(readOnly = true)
    public UploadResult result(long userId, long uploadId) {
        UploadFile upload = uploads.findById(uploadId).orElseThrow(() ->
                new UploadException(HttpStatus.NOT_FOUND, "NOT_FOUND", "업로드 기록을 찾을 수 없습니다."));
        Warehouse warehouse = warehouseRepository.findById(upload.getWarehouseId()).orElseThrow();
        if (!warehouse.getUserId().equals(userId)) {
            throw new UploadException(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.");
        }
        UploadResult result = UploadResult.from(upload);
        var runIds = jdbc.queryForList("SELECT run_id FROM run_source_files WHERE upload_file_id = ? ORDER BY run_id DESC FETCH FIRST 1 ROWS ONLY", Long.class, uploadId);
        var matchReport = matching.report(uploadId);
        return new UploadResult(result.uploadId(), result.status(), result.periodStart(), result.periodEnd(),
                result.asOfDate(), runIds.isEmpty() ? null : runIds.getFirst(), result.validation(), matchReport.matchedReceipts(), result.error(), matchReport.receiptMatching());
    }

    // reason이 SAME_PERIOD면 화면이 덮어쓰기 확인을 띄우고, SAME_FILE이면 덮어쓰기 없이 안내만 한다.
    private static UploadException duplicate(UploadFile existing, String reason, String message) {
        return new UploadException(HttpStatus.CONFLICT, "UPLOAD_DUPLICATE", message,
                Map.of("reason", reason, "existing", UploadPeriods.Existing.from(existing)));
    }
}
