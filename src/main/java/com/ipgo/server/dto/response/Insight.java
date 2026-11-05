package com.ipgo.server.dto.response;

import java.time.OffsetDateTime;

// K4 AI 인사이트. mode: AI(AI 작성 문장) · FIXED(고정 문장) · TABLE_ONLY(문장 없이 비교표만, text는 null).
// AI가 실패해 고정 문장을 쓰면 isFallback이 true다. LLM을 부르지 않는 고정 문장은 generatedAt이 null이다.
// failReason은 대체 시 공개 허용 코드만 제공하며 원문 오류는 노출하지 않는다.
public record Insight(Long resultId, String mode, boolean isFallback, String text, OffsetDateTime generatedAt, String failReason) {
}
