package com.planmate.domain.chatbot.controller;

import com.planmate.domain.chatbot.dto.ChatbotApplyRequest;
import com.planmate.domain.chatbot.dto.ChatbotApplyResponse;
import com.planmate.domain.chatbot.service.ChatbotApplyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 챗봇 [반영] — working copy 를 저장본에 반영 (설계 §4 단계7).
 *
 * <p>REST 응답 자체가 ack — {@code 200 {success, conflictDetected}}. 실패는 표준
 * {@code GlobalExceptionHandler}가 {@code ErrorCode} 로 매핑.
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §10-3.
 */
@RestController
@RequiredArgsConstructor
public class ChatbotApplyController {

    private final ChatbotApplyService chatbotApplyService;

    @PostMapping("/api/plan/{planId}/chatbot-apply")
    public ChatbotApplyResponse apply(@PathVariable UUID planId,
                                      @AuthenticationPrincipal UUID userId,
                                      @Valid @RequestBody ChatbotApplyRequest request) {
        return chatbotApplyService.apply(planId, userId, request);
    }
}
