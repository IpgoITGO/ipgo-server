package com.ipgo.server.upload;

import org.springframework.web.multipart.MultipartFile;

public interface UploadStorage {
    void precheck(MultipartFile file);

    String contentHash(MultipartFile file);

    StoredUpload store(long userId, MultipartFile file);
}
