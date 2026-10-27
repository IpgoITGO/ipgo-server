package com.ipgo.server.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@Service
public class LocalUploadStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalUploadStorage.class);
    private final Path root;
    private final long maxBytes;
    private final JsonMapper mapper;

    public LocalUploadStorage(@Value("${ipgo.upload.directory}") String directory,
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxSize, JsonMapper mapper) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.maxBytes = maxSize.toBytes();
        this.mapper = mapper;
        if (directory.isBlank() || maxBytes <= 0) throw new IllegalArgumentException("업로드 저장 설정이 올바르지 않습니다.");
    }

    public StoredUpload store(long userId, MultipartFile file) {
        if (userId <= 0) throw new IllegalArgumentException("회원 ID가 올바르지 않습니다.");
        String name = file.getOriginalFilename();
        validateName(name);
        if (file.isEmpty()) throw invalid("빈 파일은 업로드할 수 없습니다.");
        if (file.getSize() > maxBytes) throw tooLarge();

        Path pending = null;
        try {
            Path userDirectory = Files.createDirectories(root).toRealPath().resolve(Long.toString(userId));
            Files.createDirectories(userDirectory);
            if (!Files.isDirectory(userDirectory, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            // shortcut: 단일 서버의 로컬 디스크를 사용한다. 다중 서버 배포 전 공유 저장소로 이관한다.
            pending = Files.createTempDirectory(userDirectory, ".pending-");
            Path original = pending.resolve("original.xlsx");
            MessageDigest digest = sha256();
            long size = copy(file, original, digest);
            XlsxValidator.validate(original);
            UUID id = UUID.randomUUID();
            StoredUpload result = new StoredUpload(id, name, size, HexFormat.of().formatHex(digest.digest()),
                    Instant.now(), "STORED");
            Files.writeString(pending.resolve("metadata.json"),
                    mapper.writeValueAsString(new UploadMetadata(userId, result)), StandardOpenOption.CREATE_NEW);
            // 원본과 메타데이터가 모두 준비된 디렉터리만 완료 위치로 옮긴다.
            Files.move(pending, userDirectory.resolve(id.toString()), StandardCopyOption.ATOMIC_MOVE);
            pending = null;
            return result;
        } catch (IOException exception) {
            log.error("로컬 업로드 저장에 실패했습니다. 오류 유형: {}", exception.getClass().getSimpleName());
            throw new UploadException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_STORAGE_ERROR",
                    "파일을 보관하지 못했습니다. 잠시 후 다시 시도해주세요.");
        } finally {
            if (pending != null) cleanPending(pending);
        }
    }

    private long copy(MultipartFile file, Path target, MessageDigest digest) throws IOException {
        long total = 0;
        try (var input = file.getInputStream();
                var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
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
            throw new UploadException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_FILE_TYPE",
                    ".xlsx 파일만 업로드할 수 있습니다.");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static UploadException invalid(String message) {
        return new UploadException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    static UploadException tooLarge() {
        return new UploadException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "파일 크기 제한을 초과했습니다.");
    }

    private static void cleanPending(Path pending) {
        try {
            Files.deleteIfExists(pending.resolve("metadata.json"));
            Files.deleteIfExists(pending.resolve("original.xlsx"));
            Files.deleteIfExists(pending);
        } catch (IOException exception) {
            log.error("업로드 임시 파일 정리가 필요합니다. 오류 유형: {}", exception.getClass().getSimpleName());
        }
    }

    private record UploadMetadata(long userId, StoredUpload upload) { }
}
