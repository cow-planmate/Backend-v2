package com.planmate.domain.chatbot.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 프론트 → v2 챗봇 대화 턴 요청. 프론트가 저작하는 필드만 담는다 —
 * {@code planId} 는 path, {@code planContext}/{@code userContext} 는 v2 가 조립한다.
 *
 * <p>{@code pendingContext}/{@code shownPlaces}/{@code recentMessages} 는 v2 가 손대지 않고
 * planmate-ai 요청에 그대로 실어 보낸다(raw 통과). 셰이프 진실 = planmate-ai/project_ai/13 · 12 §1-1a.
 *
 * <p>설계: project_ai/04_기능/ai/06_챗봇_연동_설계.md §3-1.
 */
public record ChatbotTurnRequest(

        @NotBlank
        String message,

        // nullable — 직전 턴이 플랜 변경이었을 때 프론트 working copy. v2 미검사.
        JsonNode pendingContext,

        // nullable — 직전 턴이 순수 추천이었을 때 [{contentId,title,category}] echo. v2 미검사.
        JsonNode shownPlaces,

        // 직전 사용자 발화(오래된→최신, 현재 message 제외). null/누락 → 빈 배열로 전달.
        @Size(max = 3, message = "최근 대화는 최대 3개까지 보낼 수 있습니다.")
        List<@Size(max = 500, message = "각 메시지는 500자를 넘을 수 없습니다.") String> recentMessages
) {
}
