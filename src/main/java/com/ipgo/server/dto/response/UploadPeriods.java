package com.ipgo.server.dto.response;

import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.entity.UploadPeriod;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

// U3 이미 올린 기간 목록. 기간마다 가장 마지막에 올린 파일 1개씩.
public record UploadPeriods(List<Item> items, Long latestUploadId) {
    // 화면에 보여줄 업로드 일시는 명세서 예시처럼 한국 시간(+09:00)으로 돌려준다.
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public record Item(String period, Long uploadId, String fileName, LocalDate periodStart, LocalDate periodEnd,
            OffsetDateTime uploadedAt, String status) {

        public static Item from(UploadFile upload) {
            return new Item(new UploadPeriod(YearMonth.from(upload.getPeriodStart())).label(),
                    upload.getId(), upload.getOriginalName(), upload.getPeriodStart(), upload.getPeriodEnd(),
                    seoul(upload.getCreatedAt()), upload.getStatus());
        }
    }

    // 409 UPLOAD_DUPLICATE의 details.existing
    public record Existing(Long uploadId, String originalName, LocalDate periodStart, LocalDate periodEnd,
            OffsetDateTime uploadedAt) {

        public static Existing from(UploadFile upload) {
            return new Existing(upload.getId(), upload.getOriginalName(), upload.getPeriodStart(),
                    upload.getPeriodEnd(), seoul(upload.getCreatedAt()));
        }
    }

    private static OffsetDateTime seoul(Instant instant) {
        return instant.atZone(SEOUL).toOffsetDateTime();
    }
}
