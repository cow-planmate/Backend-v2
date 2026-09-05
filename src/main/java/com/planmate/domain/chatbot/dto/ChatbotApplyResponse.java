package com.planmate.domain.chatbot.dto;

/**
 * [반영] 응답 — REST 응답 자체가 ack(설계 §10-3). 별도 push 채널 불필요.
 * {@code conflictDetected} 는 예외가 아니라 정상 응답의 플래그(§10-1).
 */
public record ChatbotApplyResponse(boolean success, boolean conflictDetected) {
}
