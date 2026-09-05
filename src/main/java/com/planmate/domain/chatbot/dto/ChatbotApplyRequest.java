package com.planmate.domain.chatbot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * [반영] 요청 — 프론트가 working copy 전체를 보낸다.
 *
 * <p>{@code sessionId} 필드가 없다(2026-09-05 갱신) — raw-WS wire 프로토콜이 클라이언트에게 자기
 * 세션 id를 알려주는 수단이 없어(Hello 프레임 = schema_hash 뿐), v2가 {@code userId}+{@code planId}
 * 로 {@code WebSocketSessionRegistry.sessionIdsOf}를 직접 조회해 세션을 찾는다(설계 §9-1). 트레이드
 * 오프(다중 탭 시 undo 귀속 부정확)는 설계 §12-1.
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §2.
 */
public record ChatbotApplyRequest(

        @NotNull
        @Valid
        ChatbotApplyPlanDto plan
) {
}
