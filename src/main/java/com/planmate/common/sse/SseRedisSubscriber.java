package com.planmate.common.sse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis SSE 채널을 구독해서 이 노드에 로컬로 붙어있는 emitter로 전달한다.
 * 모든 노드가 같은 채널을 구독하므로, 어느 노드가 발행하든 전체 노드에 전파된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseRedisSubscriber implements MessageListener {

    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            SseNotificationMessage msg = objectMapper.readValue(body, SseNotificationMessage.class);
            UUID userId = UUID.fromString(msg.getUserId());
            sseEmitterService.deliverToLocalEmitters(userId, msg.getEventName(), msg.getData());
        } catch (Exception e) {
            log.warn("SSE Redis 메시지 처리 실패: {}", e.getMessage());
        }
    }
}
