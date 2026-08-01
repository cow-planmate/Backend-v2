package com.planmate.domain.user.internal;

import com.planmate.domain.user.event.UserProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 전환기 전용 — 커뮤니티 소유의 Redis 캐시 키를 여기서 직접 지운다.
 *
 * 원래 무효화 방식이 이것이었다. 두 서비스가 같은 Redis를 쓴다는 점에 기대어 남의
 * 키스페이스를 지우는 구조라, 이대로면 Redis를 분리할 수 없고 커뮤니티가 키 prefix를
 * 바꾸면 여기서 조용히 무효화가 멈춘다. 그래서 UserProfileChangedListener 의
 * gRPC 스트림 알림으로 대체하는 중이다.
 *
 * 그럼에도 지금 남겨두는 이유는 <b>아직 스트림을 구독하지 않는 구버전 커뮤니티가
 * 떠 있을 수 있어서</b>다. 두 경로가 같은 키를 지우므로 중복 삭제는 무해하다.
 *
 * <b>Redis 분리 시점에 이 파일을 통째로 지울 것.</b> 그때가 되면 이 코드는 동작하지도
 * 않는다(각 서비스가 자기 Redis만 본다).
 *
 * @deprecated Redis 분리와 함께 제거한다.
 */
@Deprecated(forRemoval = true)
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyCommunityCacheEvictor {

    // 커뮤니티 UserClient 의 캐시 키와 맞물린 계약이다 — 한쪽만 바꾸면 조용히 어긋난다
    private static final String PROFILE_KEY_PREFIX = "community:user:profile:";
    private static final String VISIBILITY_KEY_PREFIX = "community:user:profile-public:";

    private final StringRedisTemplate redisTemplate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(UserProfileChangedEvent event) {
        try {
            redisTemplate.delete(List.of(
                    PROFILE_KEY_PREFIX + event.userId(),
                    VISIBILITY_KEY_PREFIX + event.userId()
            ));
        } catch (Exception e) {
            // best-effort — 못 지워도 TTL이 만료되면 최신 값으로 수렴한다
            log.warn("커뮤니티 사용자 캐시 무효화 실패 (userId={}): {}", event.userId(), e.getMessage());
        }
    }
}
