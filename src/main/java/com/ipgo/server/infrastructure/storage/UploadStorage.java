package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.dto.StoredUpload;
import com.ipgo.server.entity.UploadFile;

import org.springframework.web.multipart.MultipartFile;

public interface UploadStorage {
    void precheck(MultipartFile file);

    String contentHash(MultipartFile file);

    StoredUpload store(long userId, MultipartFile file);

    default String kind() { return "EXTERNAL"; }

    // Called within the upload transaction after the IDENTITY upload row exists.
    default void persistOriginal(UploadFile upload, MultipartFile file) { }
}
