package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.dto.StoredUpload;
import com.ipgo.server.exception.UploadException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@ConditionalOnProperty(name = "ipgo.upload.storage", havingValue = "s3")
public class S3UploadStorage implements UploadStorage {
    private static final Logger log = LoggerFactory.getLogger(S3UploadStorage.class);
    private final S3Client s3;
    private final String bucket;
    private final long maxBytes;

    public S3UploadStorage(S3Client s3, @Value("${ipgo.upload.bucket}") String bucket,
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxSize) {
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("S3 bucket 설정이 필요합니다.");
        this.s3 = s3;
        this.bucket = bucket;
        this.maxBytes = maxSize.toBytes();
    }

    @Override
    public void precheck(MultipartFile file) {
        validateName(file.getOriginalFilename());
        if (file.isEmpty()) throw invalid("빈 파일은 업로드할 수 없습니다.");
        if (file.getSize() > maxBytes) throw tooLarge();
    }

    @Override
    public String contentHash(MultipartFile file) {
        MessageDigest digest = sha256();
        try (var input = new DigestInputStream(file.getInputStream(), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        } catch (IOException exception) {
            throw storageError(exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public StoredUpload store(long userId, MultipartFile file) {
        if (userId <= 0) throw new IllegalArgumentException("회원 ID가 올바르지 않습니다.");
        precheck(file);
        UUID id = UUID.randomUUID();
        Path temporary = null;
        try {
            temporary = Files.createTempFile("ipgo-upload-", ".xlsx");
            MessageDigest digest = sha256();
            long size = copy(file, temporary, digest);
            XlsxValidator.validate(temporary);
            String hash = HexFormat.of().formatHex(digest.digest());
            String key = userId + "/" + id + "/original.xlsx";
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .metadata(Map.of("sha256", hash))
                    .build();
            s3.putObject(request, RequestBody.fromFile(temporary));
            return new StoredUpload(id, file.getOriginalFilename(), size, hash, Instant.now(), "STORED");
        } catch (IOException | SdkException exception) {
            throw storageError(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    log.warn("업로드 임시 파일을 정리하지 못했습니다. 오류 유형: {}",
                            exception.getClass().getSimpleName());
                }
            }
        }
    }

    private long copy(MultipartFile file, Path target, MessageDigest digest) throws IOException {
        long total = 0;
        try (var input = file.getInputStream(); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw tooLarge();
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) throw invalid("빈 파일은 업로드할 수 없습니다.");
        return total;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
                || name.contains(":") || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("파일명이 올바르지 않습니다.");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            String reason = ".xlsx 파일만 업로드할 수 있습니다.";
            throw new UploadException(HttpStatus.BAD_REQUEST, "UPLOAD_INVALID_FORMAT", reason,
                    Map.of("fileName", name, "reason", reason));
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UploadException invalid(String message) {
        return new UploadException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    private static UploadException tooLarge() {
        return new UploadException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "파일 크기 제한을 초과했습니다.");
    }

    private static UploadException storageError(Exception exception) {
        log.error("S3 업로드 저장에 실패했습니다. 오류 유형: {}", exception.getClass().getSimpleName());
        return new UploadException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_STORAGE_ERROR",
                "파일을 보관하지 못했습니다. 잠시 후 다시 시도해주세요.");
    }
}
