package com.planmate.domain.chatbot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.planmate.domain.chatbot.dto.ChatbotTurnRequest;
import com.planmate.domain.chatbot.service.ChatbotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 챗봇 대화 턴 — 프론트 ↔ v2 ↔ planmate-ai 왕복 (설계 §2).
 *
 * <p>액션성 처리(리소스 생성 아님) + 바디 반환 → 200 (표준 §12).
 * planmate-ai 응답 JSON 을 가공 없이 그대로 전달한다(설계 §3-3).
 */
@RestController
@RequestMapping("/api/plan/{planId}/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping
    public ResponseEntity<JsonNode> chat(@PathVariable UUID planId,
                                         @AuthenticationPrincipal UUID userId,
                                         @Valid @RequestBody ChatbotTurnRequest request) {
        return ResponseEntity.ok(chatbotService.chat(planId, userId, request));
    }
}
