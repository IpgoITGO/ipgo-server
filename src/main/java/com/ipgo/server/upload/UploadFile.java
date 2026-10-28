package com.ipgo.server.upload;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 수불부 업로드 기록. validation_report는 Python 정제 연동 때 매핑한다.
@Entity
@Table(name = "upload_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadFile {
    static final String PROCESSING = "PROCESSING";
    static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "original_name", nullable = false, updatable = false, length = 255)
    private String originalName;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "file_hash", nullable = false, updatable = false, columnDefinition = "CHAR(64)")
    private String fileHash;

    @Column(name = "revision_no", nullable = false, updatable = false)
    private Integer revisionNo;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "job_id", length = 100)
    private String jobId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    UploadFile(Long warehouseId, String originalName, String storageKey, String fileHash, int revisionNo,
            UploadPeriod period) {
        this.warehouseId = warehouseId;
        this.originalName = originalName;
        this.storageKey = storageKey;
        this.fileHash = fileHash;
        this.revisionNo = revisionNo;
        this.periodStart = period.start();
        this.periodEnd = period.end();
        this.status = PROCESSING;
        this.createdAt = Instant.now();
    }
}
