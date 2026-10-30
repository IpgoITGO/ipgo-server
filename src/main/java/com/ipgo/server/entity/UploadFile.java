package com.ipgo.server.entity;

import com.ipgo.server.dto.UploadProcessingReport;
import java.util.UUID;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 수불부 업로드 기록과 정제 결과. 원본과 revision은 완료 후에도 보존한다.
@Entity
@Table(name = "upload_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadFile {
    static final String PROCESSING = "PROCESSING";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private Long warehouseId;

    @Column(name = "original_name", nullable = false, updatable = false, length = 255)
    private String originalName;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    // DB는 고정 길이 CHAR(64)다. JDBC 타입도 CHAR로 맞춰야 PostgreSQL 스키마 검증을 통과한다.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "file_hash", nullable = false, updatable = false, length = 64)
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_report", columnDefinition = "jsonb")
    private UploadProcessingReport validationReport;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UploadFile(Long warehouseId, String originalName, String storageKey, String fileHash, int revisionNo,
            UploadPeriod period) {
        this.warehouseId = warehouseId;
        this.originalName = originalName;
        this.storageKey = storageKey;
        this.fileHash = fileHash;
        this.revisionNo = revisionNo;
        this.periodStart = period.start();
        this.periodEnd = period.end();
        this.status = PROCESSING;
        this.jobId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public boolean isProcessing() { return PROCESSING.equals(status); }

    public void complete(LocalDate periodEnd, UploadProcessingReport report) {
        if (!isProcessing()) throw new IllegalStateException("Upload already finished");
        this.periodEnd = periodEnd;
        this.validationReport = report;
        this.status = "DONE";
    }

    public void fail(UploadProcessingReport.Failure failure) {
        if (!isProcessing()) throw new IllegalStateException("Upload already finished");
        this.validationReport = new UploadProcessingReport(null, null, null, failure);
        this.status = FAILED;
    }
}
