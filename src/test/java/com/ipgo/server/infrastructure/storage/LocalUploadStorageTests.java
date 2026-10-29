package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.dto.StoredUpload;
import com.ipgo.server.exception.UploadException;
import com.ipgo.server.support.UploadTestFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalUploadStorageTests {
    @TempDir Path root;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void storesExactBytesAndDurableMetadataWithoutOverwritingSameName() throws Exception {
        byte[] bytes = UploadTestFiles.workbook();
        var first = storage().store(12, file("수불부.xlsx", bytes));
        var second = storage().store(12, file("수불부.xlsx", bytes));
        assertThat(first.uploadId()).isNotEqualTo(second.uploadId());
        assertThat(first.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        for (var result : new StoredUpload[] {first, second}) {
            Path directory = root.resolve("12").resolve(result.uploadId().toString());
            assertThat(Files.readAllBytes(directory.resolve("original.xlsx"))).isEqualTo(bytes);
            var metadata = mapper.readTree(Files.readString(directory.resolve("metadata.json")));
            assertThat(metadata.get("userId").longValue()).isEqualTo(12);
            assertThat(metadata.get("upload").get("originalName").stringValue()).isEqualTo("수불부.xlsx");
            assertThat(metadata.get("upload").get("status").stringValue()).isEqualTo("STORED");
        }
        var afterRestart = storage().store(12, file("수불부.xlsx", bytes));
        assertThat(afterRestart.uploadId()).isNotEqualTo(first.uploadId());
        try (var entries = Files.list(root.resolve("12"))) { assertThat(entries.count()).isEqualTo(3); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.xlsx", "folder/name.xlsx", "C:\\name.xlsx", "name\n.xlsx", "", "stock.xls", "stock.xlsm", "stock.csv"})
    void rejectsUnsafeOrUnsupportedNames(String name) throws Exception {
        assertThatThrownBy(() -> storage().store(12, file(name, UploadTestFiles.workbook())))
                .isInstanceOf(UploadException.class);
        assertNoFiles();
    }

    @Test
    void rejectsEmptyOversizeAndTruncatedFiles() throws Exception {
        assertThatThrownBy(() -> storage().store(12, file("stock.xlsx", new byte[0])))
                .isInstanceOf(UploadException.class);
        var small = new LocalUploadStorage(root.toString(), DataSize.ofBytes(1), mapper);
        assertThatThrownBy(() -> small.store(12, file("stock.xlsx", UploadTestFiles.workbook())))
                .isInstanceOfSatisfying(UploadException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThatThrownBy(() -> storage().store(12, file("stock.xlsx", new byte[] {'P', 'K', 3, 4})))
                .isInstanceOf(UploadException.class);
        assertNoFiles();
    }

    @Test
    void rejectsFakePackagesMacrosXmlEntitiesAndExpandedZipBombs() throws Exception {
        var macro = UploadTestFiles.parts();
        macro.put("xl/vbaProject.bin", "test");
        var xml = UploadTestFiles.parts();
        xml.put("xl/workbook.xml", "<!DOCTYPE workbook [<!ENTITY x SYSTEM 'file:///not-read'>]><workbook>&x;</workbook>");
        var wrongType = UploadTestFiles.parts();
        wrongType.put("[Content_Types].xml", "<Types/>");
        var brokenRelationships = UploadTestFiles.parts();
        brokenRelationships.put("_rels/.rels", "not xml");
        for (byte[] bytes : new byte[][] {UploadTestFiles.zip(Map.of("text.txt", "test"), 0),
                UploadTestFiles.zip(macro, 0), UploadTestFiles.zip(xml, 0),
                UploadTestFiles.zip(wrongType, 0), UploadTestFiles.zip(brokenRelationships, 0),
                UploadTestFiles.zip(UploadTestFiles.parts(), 101)}) {
            assertThatThrownBy(() -> storage().store(12, file("stock.xlsx", bytes)))
                    .isInstanceOf(UploadException.class);
        }
        assertNoFiles();
    }

    @Test
    void boundsActualStreamEvenWhenReportedSizeIsSmaller() throws Exception {
        var small = new LocalUploadStorage(root.toString(), DataSize.ofBytes(10), mapper);
        var dishonest = new MockMultipartFile("file", "stock.xlsx", "application/octet-stream", new byte[11]) {
            @Override public long getSize() { return 1; }
        };
        assertThatThrownBy(() -> small.store(12, dishonest)).isInstanceOf(UploadException.class);
        assertNoFiles();
    }

    @Test
    void cleansPartialFilesOnReadFailureAndHidesStoragePath() throws Exception {
        var broken = new MockMultipartFile("file", "stock.xlsx", "application/octet-stream", new byte[] {1}) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    private int read;
                    @Override public int read() throws IOException {
                        if (read++ < 4) return 1;
                        throw new IOException("test-only-private-path");
                    }
                };
            }
        };
        assertThatThrownBy(() -> storage().store(12, broken))
                .isInstanceOfSatisfying(UploadException.class, e -> {
                    assertThat(e.code()).isEqualTo("UPLOAD_STORAGE_ERROR");
                    assertThat(e.getMessage()).doesNotContain(root.toString(), "test-only-private-path");
                });
        assertNoFiles();
    }

    @Test
    void rejectsSymlinkedUserDirectoryAndUnavailableStorage() throws Exception {
        Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
        Files.createSymbolicLink(root.resolve("12"), elsewhere);
        assertThatThrownBy(() -> storage().store(12, file("stock.xlsx", UploadTestFiles.workbook())))
                .isInstanceOf(UploadException.class);
        try (var entries = Files.list(elsewhere)) { assertThat(entries.count()).isZero(); }
        Path notDirectory = Files.writeString(root.resolve("not-directory"), "test");
        var blocked = new LocalUploadStorage(notDirectory.toString(), DataSize.ofMegabytes(10), mapper);
        assertThatThrownBy(() -> blocked.store(12, file("stock.xlsx", UploadTestFiles.workbook())))
                .isInstanceOf(UploadException.class);
    }

    private LocalUploadStorage storage() {
        return new LocalUploadStorage(root.toString(), DataSize.ofMegabytes(10), mapper);
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    private void assertNoFiles() throws IOException {
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(p -> Files.isRegularFile(p) || p.getFileName().toString().startsWith(".pending-")).count()).isZero();
        }
    }
}
