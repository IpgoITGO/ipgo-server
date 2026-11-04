package com.ipgo.server.dto.response;

import com.ipgo.server.entity.UploadFile;

import java.time.LocalDate;

// U1 응답. 파일 하나당 업로드 기록 하나이고, uploadId로 U2 결과를 조회한다.
public record UploadAccepted(Long uploadId, String status, String originalName,
        LocalDate periodStart, LocalDate periodEnd) {
    public static UploadAccepted from(UploadFile upload) {
        return new UploadAccepted(upload.getId(), upload.getStatus(), upload.getOriginalName(),
                upload.getPeriodStart(), upload.getPeriodEnd());
    }
}
