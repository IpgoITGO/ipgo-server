package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.exception.UploadException;
import com.ipgo.server.support.UploadTestFiles;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseUploadStorageTests {
    @Test void requiresExplicitQuotaAndValidatesOriginalBeforeDatabaseWrites() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> new DatabaseUploadStorage(jdbc, DataSize.ofMegabytes(10), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseUploadStorage(jdbc, DataSize.ofMegabytes(10), 268_435_457L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new DatabaseUploadStorage(jdbc, DataSize.ofMegabytes(10), 268_435_456L))
                .doesNotThrowAnyException();
        var storage = new DatabaseUploadStorage(jdbc, DataSize.ofMegabytes(10), 100_000);
        byte[] content = UploadTestFiles.workbook();
        var file = new MockMultipartFile("file", "2026-10.xlsx", "application/octet-stream", content);
        var metadata = storage.store(1, file);
        assertThat(storage.kind()).isEqualTo("DATABASE");
        assertThat(metadata.size()).isEqualTo(content.length);
        assertThat(metadata.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        verifyNoInteractions(jdbc);
    }

    @Test void rejectsInvalidAndActualOversizeSourceWithoutDatabaseWrites() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var storage = new DatabaseUploadStorage(jdbc, DataSize.ofBytes(10), 100_000);
        var dishonest = new MockMultipartFile("file", "2026-10.xlsx", "application/octet-stream", new byte[11]) {
            @Override public long getSize() { return 1; }
        };
        assertThatThrownBy(() -> storage.store(1, dishonest)).isInstanceOf(UploadException.class);
        var normal = new DatabaseUploadStorage(jdbc, DataSize.ofMegabytes(10), 100_000);
        for (var file : new MockMultipartFile[] {
                new MockMultipartFile("file", "../2026-10.xlsx", "application/octet-stream", new byte[]{1}),
                new MockMultipartFile("file", "2026-10.xlsx", "application/octet-stream", new byte[]{'P','K',3,4}),
                new MockMultipartFile("file", "2026-10.xls", "application/octet-stream", new byte[]{1})}) {
            assertThatThrownBy(() -> normal.store(1, file)).isInstanceOf(UploadException.class);
        }
        verifyNoInteractions(jdbc);
    }
}
