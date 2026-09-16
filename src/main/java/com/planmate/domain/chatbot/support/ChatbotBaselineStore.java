package com.planmate.domain.chatbot.support;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 챗봇 [대화]-[반영] 사이 충돌 감지용 content fingerprint 저장소.
 *
 * <p>키 {@code chatbot:baseline:{planId}:{userId}}, TTL 30분 — SharedSync 캐시(Redis)와는 별개
 * 네임스페이스. 표준 §5-3(도메인별 얇은 Redis 래퍼)에 따라 Service가 {@link RedisTemplate}을
 * 직접 만지지 않고 이 클래스만 호출한다.
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §10-1-1.
 */
@Component
@RequiredArgsConstructor
public class ChatbotBaselineStore {

    private static final String KEY_PREFIX = "chatbot:baseline:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, Object> redisTemplate;

    public void save(UUID planId, UUID userId, String fingerprint) {
        redisTemplate.opsForValue().set(key(planId, userId), fingerprint, TTL);
    }

    /** 없으면(첫 턴이거나 TTL 만료) null. */
    public String find(UUID planId, UUID userId) {
        Object value = redisTemplate.opsForValue().get(key(planId, userId));
        return value == null ? null : value.toString();
    }

    private String key(UUID planId, UUID userId) {
        return KEY_PREFIX + planId + ":" + userId;
    }
}
