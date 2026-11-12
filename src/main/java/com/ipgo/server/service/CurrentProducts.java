package com.ipgo.server.service;

import com.ipgo.server.entity.UploadFile;
import com.ipgo.server.repository.UploadFileRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// #188 같은 달 수불부를 덮어써서 기록이 사라진 상품.
// 예: 9월 쿠키·껌, 10월 테스트 파일에 사탕 → 실제 10월 파일(쿠키)로 덮어쓰면 사탕만 여기에 들어간다.
// 기준 파일은 다른 화면 · 계산과 같다: 달마다 최신 revision을 고른 뒤 정제가 끝난(DONE) 파일.
// - 이번 달에만 없는 상품(품절 등)은 다른 달 기준 파일에 기록이 있어서 들어가지 않는다.
// - 업로드 기록이 아예 없는 상품은 덮어쓴 것이 아니라서 들어가지 않는다.
// - 상품 행은 지우지 않으므로 설정은 남고, 그 바코드가 새 파일에 다시 나오면 자동으로 빠진다.
@Component
public class CurrentProducts {
    private final UploadFileRepository uploads;
    private final NamedParameterJdbcTemplate jdbc;

    public CurrentProducts(UploadFileRepository uploads, NamedParameterJdbcTemplate jdbc) {
        this.uploads = uploads;
        this.jdbc = jdbc;
    }

    public Set<Long> overwritten(long warehouseId) {
        List<Long> sources = uploads.findLatestPerPeriod(warehouseId).stream()
                .filter(u -> "DONE".equals(u.getStatus())).map(UploadFile::getId).toList();
        // 기준 파일이 하나도 없으면(첫 정제 중 등) 판단할 수 없으니 아무것도 숨기지 않는다.
        if (sources.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT d.product_id FROM product_daily_stocks d JOIN upload_files u ON u.id = d.upload_file_id
                WHERE u.warehouse_id = :warehouse
                  AND d.product_id NOT IN (SELECT c.product_id FROM product_daily_stocks c WHERE c.upload_file_id IN (:sources))
                """, new MapSqlParameterSource("warehouse", warehouseId).addValue("sources", sources), Long.class));
    }
}
