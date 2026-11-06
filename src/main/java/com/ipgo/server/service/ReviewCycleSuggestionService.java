package com.ipgo.server.service;

import com.ipgo.server.entity.Warehouse;
import com.ipgo.server.repository.UploadFileRepository;
import com.ipgo.server.dto.response.ProductSettings;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Service;

// 조회 전용 제안. 설정 저장/계산 시작은 사용자의 S2 확인 뒤에만 수행한다.
@Service
public class ReviewCycleSuggestionService {
    private final UploadFileRepository uploads;

    public ReviewCycleSuggestionService(UploadFileRepository uploads) { this.uploads = uploads; }

    public ProductSettings defaults(Optional<Warehouse> warehouse) {
        ProductSettings stored = ProductSettings.defaults(warehouse);
        if (warehouse.isEmpty() || warehouse.get().hasConfirmedDefaults()) return stored;
        // revision 선택을 DONE 필터보다 먼저 수행한다. 실패/처리 중 정정의 옛 값을 되살리지 않는다.
        Integer cycle = uploads.findLatestPerPeriod(warehouse.get().getId()).stream()
                .filter(u -> "DONE".equals(u.getStatus()))
                .max(Comparator.comparing(com.ipgo.server.entity.UploadFile::getPeriodStart))
                .map(u -> u.getValidationReport())
                .map(r -> r.estimatedReviewCycleDays())
                .filter(value -> value > 0).orElse(null);
        return new ProductSettings(stored.leadTimeDays(), cycle, stored.orderUnitQty());
    }
}
