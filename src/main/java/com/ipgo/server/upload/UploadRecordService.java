package com.ipgo.server.upload;

import java.util.List;
import java.util.Map;

import com.ipgo.server.product.Warehouse;
import com.ipgo.server.product.WarehouseService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

// 업로드 순서: 파일 검사 → 파일명 기간 읽기 → 같은 기간 확인 → 원본 저장 → 업로드 기록 저장 (BE-03 · BE-04)
@Service
public class UploadRecordService {
    static final String SAME_PERIOD = "SAME_PERIOD";
    static final String SAME_FILE = "SAME_FILE";

    private final LocalUploadStorage storage;
    private final WarehouseService warehouses;
    private final UploadFileRepository uploads;

    public UploadRecordService(LocalUploadStorage storage, WarehouseService warehouses, UploadFileRepository uploads) {
        this.storage = storage;
        this.warehouses = warehouses;
        this.uploads = uploads;
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
        UploadFile saved = uploads.save(new UploadFile(warehouse.getId(), stored.originalName(),
                userId + "/" + stored.uploadId(), stored.sha256(), revision, period));
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

    // reason이 SAME_PERIOD면 화면이 덮어쓰기 확인을 띄우고, SAME_FILE이면 덮어쓰기 없이 안내만 한다.
    private static UploadException duplicate(UploadFile existing, String reason, String message) {
        return new UploadException(HttpStatus.CONFLICT, "UPLOAD_DUPLICATE", message,
                Map.of("reason", reason, "existing", UploadPeriods.Existing.from(existing)));
    }
}
