package com.planmate.domain.chatbot.dto;

/**
 * [반영] 응답 — REST 응답 자체가 ack(설계 §10-3). 별도 push 채널 불필요.
 *
 * <p>{@code 200} 이 곧 성공이고 실패는 {@code GlobalExceptionHandler}가 {@code ErrorCode} 로
 * 던지므로 {@code success} 플래그는 두지 않는다(기능_구현_표준 §1·§8). 바디에 남는 건
 * {@code conflictDetected} 하나 — 예외가 아니라 정상 응답의 플래그(§10-1)라 HTTP 상태로
 * 표현할 수 없어 바디에 실어야 한다.
 */
public record ChatbotApplyResponse(boolean conflictDetected) {
}
