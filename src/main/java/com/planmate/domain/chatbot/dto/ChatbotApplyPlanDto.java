package com.planmate.domain.chatbot.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * [반영] 요청의 {@code plan} — 프론트 working copy 전체. {@code GetCompletePlanResponse} 와 같은
 * 3덩어리 셰이프이지만 blockId/timeTableId 가 nullable 이고 블록에 {@code date} 가 직접 실린다
 * ({@link ChatbotApplyBlockDto} 참고).
 *
 * <p>{@code planFrame} 은 [반영] 대상이 아니다(설계 §5-5 — AI 가 안 바꿈, Plan 엔티티도 안 건드림) —
 * v2 가 내용을 검사·매핑할 이유가 없어 원문 그대로 통과시키기만 하는 {@link JsonNode} 로 받는다.
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §2, §5-5.
 */
public record ChatbotApplyPlanDto(

        JsonNode planFrame,

        @NotNull
        List<@Valid ChatbotApplyTimetableDto> timetables,

        @NotNull
        List<@Valid ChatbotApplyBlockDto> placeBlocks
) {
}
