package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.dto.StoredUpload;

import org.springframework.web.multipart.MultipartFile;

public interface UploadStorage {
    void precheck(MultipartFile file);

    String contentHash(MultipartFile file);

    StoredUpload store(long userId, MultipartFile file);
}
