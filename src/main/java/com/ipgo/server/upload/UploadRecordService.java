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
    private final LocalUploadStorage storage;
    private final WarehouseService warehouses;
    private final UploadFileRepository uploads;

    public UploadRecordService(LocalUploadStorage storage, WarehouseService warehouses, UploadFileRepository uploads) {
        this.storage = storage;
        this.warehouses = warehouses;
        this.uploads = uploads;
    }

    @Transactional
    public StoredUpload upload(long userId, MultipartFile file, boolean overwrite) {
        storage.precheck(file);
        UploadPeriod period = UploadPeriod.fromFileName(file.getOriginalFilename());

        // 창고를 잠가서 같은 기간을 동시에 두 번 올려도 하나씩 확인한다.
        Warehouse warehouse = warehouses.lockDefault(userId);
        if (!overwrite) {
            uploads.findFirstByWarehouseIdAndPeriodStartAndStatusNotOrderByRevisionNoDesc(
                    warehouse.getId(), period.start(), UploadFile.FAILED)
                    .ifPresent(existing -> { throw duplicate(existing); });
        }
        int revision = uploads.lastRevision(warehouse.getId(), period.start()) + 1;

        StoredUpload stored = storage.store(userId, file);
        uploads.save(new UploadFile(warehouse.getId(), stored.originalName(), userId + "/" + stored.uploadId(),
                stored.sha256(), revision, period));
        return stored;
    }

    @Transactional(readOnly = true)
    public UploadPeriods periods(long userId) {
        return warehouses.findDefault(userId)
                .map(warehouse -> new UploadPeriods(
                        uploads.findLatestPerPeriod(warehouse.getId()).stream().map(UploadPeriods.Item::from).toList(),
                        uploads.findFirstByWarehouseIdOrderByIdDesc(warehouse.getId()).map(UploadFile::getId).orElse(null)))
                .orElse(new UploadPeriods(List.of(), null));
    }

    private static UploadException duplicate(UploadFile existing) {
        return new UploadException(HttpStatus.CONFLICT, "UPLOAD_DUPLICATE", "이미 올린 기간입니다.",
                Map.of("existing", UploadPeriods.Existing.from(existing)));
    }
}
