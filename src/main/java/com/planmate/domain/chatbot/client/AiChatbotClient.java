package com.planmate.domain.chatbot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planmate.common.exception.domain.ChatbotAiMisconfiguredException;
import com.planmate.common.exception.domain.ChatbotAiUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.UUID;

/**
 * planmate-ai {@code POST /api/chatbot} 호출.
 *
 * <p>성공(2xx) 시 응답 본문을 {@link JsonNode} 로 그대로 반환한다 — v2 는 파싱·검증하지 않고
 * 프론트로 흘린다(설계 §3-3). 에러만 도메인 예외로 변환한다(설계 §5-2):
 * <ul>
 *   <li>401 / 422 → {@link ChatbotAiMisconfiguredException} (500) — v2 요청 조립/설정 버그. 로그 ERROR.</li>
 *   <li>그 외 4xx/5xx (502·503 등) → {@link ChatbotAiUnavailableException} (503)</li>
 *   <li>connect refused / connect timeout → 1s 후 1회 재시도, 그래도 실패 시 503</li>
 *   <li>read timeout / 기타 I/O → 재시도 없이 503</li>
 * </ul>
 *
 * <p>설계: project_ai/04_기능/ai/06_챗봇_연동_설계.md §5.
 */
@Component
@Slf4j
public class AiChatbotClient {

    private static final long RETRY_BACKOFF_MS = 1_000L;

    private final RestClient ai;

    public AiChatbotClient(@Qualifier("aiRestClient") RestClient aiRestClient) {
        this.ai = aiRestClient;
    }

    /** planId 는 로그 식별용 (raw 요청 덤프는 planContext 가 커서 남기지 않는다). */
    public JsonNode call(ObjectNode body, UUID planId) {
        try {
            return post(body, planId);
        } catch (ResourceAccessException e) {
            if (isConnectFailure(e.getCause())) {
                log.warn("planmate-ai 연결 실패 — {}s 후 1회 재시도 (planId={})", RETRY_BACKOFF_MS / 1000, planId, e);
                sleepQuietly();
                try {
                    return post(body, planId);
                } catch (ResourceAccessException retryEx) {
                    log.warn("planmate-ai 재시도도 실패 (planId={})", planId, retryEx);
                    throw new ChatbotAiUnavailableException();
                }
            }
            log.warn("planmate-ai I/O 오류 — 재시도 안 함 (planId={})", planId, e);
            throw new ChatbotAiUnavailableException();
        }
    }

    private JsonNode post(ObjectNode body, UUID planId) {
        return ai.post()
                .uri("/api/chatbot")
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw mapStatus(response.getStatusCode(), planId);
                })
                .body(JsonNode.class);
    }

    private RuntimeException mapStatus(HttpStatusCode status, UUID planId) {
        int code = status.value();
        if (code == 401 || code == 422) {
            log.error("planmate-ai 응답 {} — v2 요청 조립/설정 오류 (planId={})", code, planId);
            return new ChatbotAiMisconfiguredException();
        }
        log.warn("planmate-ai 응답 {} — 일시 오류 (planId={})", code, planId);
        return new ChatbotAiUnavailableException();
    }

    private static boolean isConnectFailure(Throwable cause) {
        // read timeout 은 HttpTimeoutException 이지만 HttpConnectTimeoutException 은 아니다 → 재시도 대상 아님.
        return cause instanceof ConnectException
                || cause instanceof HttpConnectTimeoutException;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
