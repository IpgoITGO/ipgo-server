package com.ipgo.server.upload;

import java.time.Instant;
import java.util.UUID;

public record StoredUpload(UUID uploadId, String originalName, long size, String sha256,
        Instant storedAt, String status) {
}
