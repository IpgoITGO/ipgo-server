package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.dto.StoredUpload;
import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.exception.UploadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/** Opt-in bounded bytea storage. No persistent local original or JPA binary relation. */
@Service
@ConditionalOnProperty(name = "ipgo.upload.storage", havingValue = "database")
public class DatabaseUploadStorage implements UploadStorage {
    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final JdbcTemplate jdbc;
    private final int maxBytes;
    private final long totalMaxBytes;

    public DatabaseUploadStorage(JdbcTemplate jdbc,
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxSize,
            @Value("${ipgo.upload.database-max-bytes:0}") long totalMaxBytes) {
        this.jdbc = jdbc;
        this.maxBytes = (int) Math.min(maxSize.toBytes(), 10 * 1024 * 1024);
        if (maxBytes <= 0 || totalMaxBytes <= 0) {
            throw new IllegalArgumentException("DB 원본 저장에는 파일 제한과 총 보관량 설정이 필요합니다.");
        }
        this.totalMaxBytes = totalMaxBytes;
    }

    @Override public String kind() { return "DATABASE"; }

    @Override public void precheck(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
                || name.contains(":") || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("파일명이 올바르지 않습니다.");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            String reason = ".xlsx 파일만 업로드할 수 있습니다.";
            throw new UploadException(HttpStatus.BAD_REQUEST, "UPLOAD_INVALID_FORMAT", reason,
                    Map.of("fileName", name, "reason", reason));
        }
        if (file.isEmpty()) throw invalid("빈 파일은 업로드할 수 없습니다.");
        if (file.getSize() > maxBytes) throw tooLarge();
    }

    private byte[] boundedContent(MultipartFile file) {
        precheck(file);
        try (var stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw tooLarge();
            if (bytes.length == 0) throw invalid("빈 파일은 업로드할 수 없습니다.");
            return bytes;
        } catch (IOException failure) {
            throw new UploadException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_STORAGE_ERROR", "원본을 읽지 못했습니다.");
        }
    }

    @Override public String contentHash(MultipartFile file) { return hash(boundedContent(file)); }

    @Override public StoredUpload store(long userId, MultipartFile file) {
        if (userId <= 0) throw new IllegalArgumentException("회원 ID가 올바르지 않습니다.");
        byte[] bytes = boundedContent(file);
        Path temporary = null;
        try {
            temporary = Files.createTempFile("ipgo-xlsx-check-", ".xlsx");
            Files.write(temporary, bytes);
            XlsxValidator.validate(temporary);
            return new StoredUpload(UUID.randomUUID(), file.getOriginalFilename(), bytes.length,
                    hash(bytes), Instant.now(), "STORED");
        } catch (IOException failure) {
            throw new UploadException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_STORAGE_ERROR", "원본을 확인하지 못했습니다.");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException failure) { throw new IllegalStateException("원본 검증 임시 파일 정리에 실패했습니다."); }
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void persistOriginal(UploadFile upload, MultipartFile file) {
        byte[] bytes = boundedContent(file);
        if (!hash(bytes).equals(upload.getFileHash()) || !upload.getStorageKey().startsWith("db/")) {
            throw invalid("검증한 원본과 저장할 원본이 다릅니다.");
        }
        // PostgreSQL transaction lock serializes quota checks across all app instances/warehouses.
        jdbc.execute("SELECT pg_advisory_xact_lock(7601, 1)");
        long used = jdbc.queryForObject("SELECT COALESCE(SUM(size_bytes), 0) FROM upload_originals", Long.class);
        if (used > totalMaxBytes - bytes.length) {
            throw new UploadException(HttpStatus.INSUFFICIENT_STORAGE, "UPLOAD_STORAGE_FULL", "원본 보관 한도에 도달했습니다.");
        }
        if (jdbc.update("UPDATE upload_files SET storage_kind = 'DATABASE' WHERE id = ? AND storage_kind = 'EXTERNAL'",
                upload.getId()) != 1) throw new IllegalStateException("원본 저장 대상이 올바르지 않습니다.");
        jdbc.update("INSERT INTO upload_originals(upload_file_id, content, content_type, size_bytes) VALUES (?, ?, ?, ?)",
                upload.getId(), bytes, CONTENT_TYPE, bytes.length);
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static UploadException invalid(String message) {
        return new UploadException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }
    private static UploadException tooLarge() {
        return new UploadException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "파일 크기 제한을 초과했습니다.");
    }
}
