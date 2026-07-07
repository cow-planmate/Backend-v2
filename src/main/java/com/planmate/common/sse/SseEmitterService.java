package com.planmate.common.sse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.config.RedisConnectionConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * SSE 구독 등록/보관과 멀티 노드 알림 전달을 담당한다.
 *
 * 전달 흐름: sendNotification()이 이벤트를 Redis Pub/Sub 채널({@link RedisConnectionConfig#SSE_CHANNEL})에
 * 발행하면, 모든 노드의 {@link SseRedisSubscriber}가 이를 수신해 각자 자기 노드에 붙어있는 emitter에만
 * deliverToLocalEmitters()로 전달한다(발행 노드 자신도 동일 경로를 타므로 중복 전달 경로가 없다).
 * Redis 발행이 실패하면 로컬 전달로 폴백한다.
 */
@Slf4j
@Service
public class SseEmitterService {

    private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30분

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private final StringRedisTemplate sseStringRedisTemplate;
    private final ObjectMapper objectMapper;

    public SseEmitterService(
            @Qualifier("sseStringRedisTemplate") StringRedisTemplate sseStringRedisTemplate,
            ObjectMapper objectMapper) {
        this.sseStringRedisTemplate = sseStringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        CopyOnWriteArrayList<SseEmitter> userEmitters =
                emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        userEmitters.add(emitter);

        Runnable cleanup = () -> {
            CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    emitters.remove(userId, list);
                }
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"));
        } catch (IOException e) {
            cleanup.run();
        }

        return emitter;
    }

    public void sendNotification(UUID userId, String eventName, Object data) {
        SseNotificationMessage message = new SseNotificationMessage(userId.toString(), eventName, data);
        try {
            String json = objectMapper.writeValueAsString(message);
            sseStringRedisTemplate.convertAndSend(RedisConnectionConfig.SSE_CHANNEL, json);
        } catch (JsonProcessingException e) {
            log.warn("SSE 알림 직렬화 실패 (userId: {}, event: {}): {}", userId, eventName, e.getMessage());
            deliverToLocalEmitters(userId, eventName, data);
        } catch (Exception e) {
            log.warn("SSE Redis publish 실패 — 로컬 전달로 폴백 (userId: {}, event: {}): {}", userId, eventName, e.getMessage());
            deliverToLocalEmitters(userId, eventName, data);
        }
    }

    /** {@link SseRedisSubscriber}가 Redis 메시지 수신 후 호출 — 이 노드에 붙은 emitter에만 전달. */
    public void deliverToLocalEmitters(UUID userId, String eventName, Object data) {
        CopyOnWriteArrayList<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return;
        }

        userEmitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                return false;
            } catch (IOException e) {
                log.warn("SSE 전송 실패 (userId: {}, event: {}): {}", userId, eventName, e.getMessage());
                return true;
            }
        });

        if (userEmitters.isEmpty()) {
            emitters.remove(userId, userEmitters);
        }
    }

    /** 30초마다 하트비트를 보내 리버스 프록시가 조용한 연결을 끊는 것을 방지한다. */
    @Scheduled(fixedDelay = 30_000)
    public void sendHeartbeat() {
        emitters.forEach((userId, userEmitters) -> {
            userEmitters.removeIf(emitter -> {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    return false;
                } catch (IOException e) {
                    log.debug("SSE heartbeat 실패 (userId: {}): {}", userId, e.getMessage());
                    return true;
                }
            });
            if (userEmitters.isEmpty()) {
                emitters.remove(userId, userEmitters);
            }
        });
    }
}
