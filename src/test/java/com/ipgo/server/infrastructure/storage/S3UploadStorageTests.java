package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.dto.StoredUpload;
import com.ipgo.server.support.UploadTestFiles;

import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class S3UploadStorageTests {
    @Test
    void validatesAndStoresOriginalWithPrivateObjectKey() throws Exception {
        S3Client s3 = mock(S3Client.class);
        byte[] bytes = UploadTestFiles.workbook();
        var file = new MockMultipartFile("file", "수불부.xlsx", "application/octet-stream", bytes);
        var storage = new S3UploadStorage(s3, "private-upload-bucket", DataSize.ofMegabytes(10));

        StoredUpload stored = storage.store(12, file);

        assertThat(stored.sha256())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        verify(s3).putObject(
                org.mockito.ArgumentMatchers.argThat((PutObjectRequest request) ->
                        request.bucket().equals("private-upload-bucket")
                                && request.key().equals("12/" + stored.uploadId() + "/original.xlsx")
                                && request.metadata().get("sha256").equals(stored.sha256())),
                any(RequestBody.class));
    }
}
