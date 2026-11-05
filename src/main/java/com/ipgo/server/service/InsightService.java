package com.ipgo.server.service;

import com.ipgo.server.dto.response.Insight;
import com.ipgo.server.entity.CalculationResult;
import com.ipgo.server.entity.Product;
import com.ipgo.server.exception.ProductException;
import com.ipgo.server.repository.CalculationResultRepository;
import com.ipgo.server.repository.ProductRepository;
import com.ipgo.server.util.DisplayQuantities;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// K4 AI 인사이트 1차 (BE-09 · BE-14). LLM을 부르지 않는 상태는 고정 문장을 바로 돌려주고,
// 정상 결과는 ai_explanations에 저장된 문장을 읽는다. FastAPI 호출과 저장은 2차에서 붙인다.
@Service
public class InsightService {
    static final String AI = "AI";
    static final String FIXED = "FIXED";
    static final String TABLE_ONLY = "TABLE_ONLY";
    static final String LLM_DISABLED = "LLM_DISABLED";

    private final CalculationResultRepository results;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;

    public InsightService(CalculationResultRepository results, ProductRepository products, JdbcTemplate jdbc) {
        this.results = results;
        this.products = products;
        this.jdbc = jdbc;
    }

    // 정상 결과인데 아직 저장된 문장이 없으면 empty다. 화면은 "생성 중"을 보여주고 다시 조회한다.
    @Transactional(readOnly = true)
    public Optional<Insight> insight(long userId, long resultId) {
        CalculationResult result = results.findById(resultId).orElseThrow(ProductException::notFound);
        Product product = products.findById(result.getProductId()).orElseThrow(ProductException::notFound);
        if (!product.getUserId().equals(userId)) throw ProductException.forbidden();

        if (!CalculationResult.OK.equals(result.getStatus())) return Optional.of(fixed(result));
        List<Insight> stored = jdbc.query("""
                SELECT mode, reason_code, rendered_text, created_at FROM ai_explanations WHERE result_id = ? AND status NOT IN ('PENDING', 'PROCESSING')
                """, (rs, i) -> stored(resultId, rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4)), resultId);
        return stored.stream().findFirst();
    }

    // ipgo-ai 0002의 mode(LLM · TEMPLATE · NONE)를 명세 이름으로 바꾼다. 실패 사유가 있으면 LLM을 불렀다가 실패한 것이다.
    // 단, LLM을 꺼 둬서 부르지 않은 것(LLM_DISABLED, #74)은 실패가 아니다.
    private static Insight stored(long resultId, String mode, String reasonCode, String text, Timestamp createdAt) {
        String shown = switch (mode) {
            case "LLM" -> AI;
            case "TEMPLATE" -> FIXED;
            case "NONE" -> TABLE_ONLY;
            default -> mode;
        };
        boolean fallback = !AI.equals(shown) && reasonCode != null && !LLM_DISABLED.equals(reasonCode);
        return new Insight(resultId, shown, fallback, TABLE_ONLY.equals(shown) ? null : text,
                createdAt == null ? null
                        : createdAt.toInstant().atZone(DashboardService.SEOUL).toOffsetDateTime());
    }

    // LLM을 부르지 않는 상태의 기본 문장 (ipgo-ai docs/ai-insight/prompt-and-fallback.md 6장).
    // 필요한 값이 비어 있으면 문장 없이 비교표만 보여준다.
    static Insight fixed(CalculationResult result) {
        String reason = result.getReason() == null ? null : result.getReason().strip().replaceAll("\\.+$", "");
        String text = switch (result.getStatus()) {
            case CalculationResult.NOT_CALCULABLE ->
                    reason == null ? null : "이 상품은 권장 입고량을 계산하지 못했습니다. 사유: " + reason + ".";
            case CalculationResult.EXCLUDED ->
                    reason == null ? null : "이 상품은 재고 기록 검산이 맞지 않아 이번 계산에서 제외했습니다. 사유: " + reason + ".";
            case CalculationResult.REUPLOAD_REQUIRED ->
                    reason == null ? null : "이 상품은 수불부를 다시 올려야 계산할 수 있습니다. 사유: " + reason + ".";
            case CalculationResult.NOT_NEEDED -> notNeeded(result);
            default -> null;
        };
        return new Insight(result.getId(), text == null ? TABLE_ONLY : FIXED, false, text, null);
    }

    private static String notNeeded(CalculationResult result) {
        Long forecast = DisplayQuantities.demand(result.getForecastOutboundQty());
        Long startStock = DisplayQuantities.stock(result.getStartStockQty());
        if (result.getMethod() == null || forecast == null || startStock == null) return null;
        long planned = result.getPlannedReceiptQty() == null ? 0 : result.getPlannedReceiptQty();
        return ("계산식 %s로 예측한 커버 기간 %,d일의 예상 출고량은 %,d개이고, "
                + "현재 재고 %,d개와 도착 예정 물량 %,d개로 충당되어 이번에는 입고가 필요하지 않습니다.").formatted(result.getMethod(), result.getHorizonDays(), forecast, startStock, planned);
    }
}
