package com.planmate.common.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * 커뮤니티 서비스가 들고 있는 사용자 캐시를 무효화한다.
 *
 * 커뮤니티는 게시글·댓글 작성자 정보를 {@code /api/internal/users}로 받아 Redis에 10분간 캐싱하는데,
 * 그대로 두면 프로필 사진이나 닉네임을 바꿔도 남의 피드·댓글에는 최대 10분간 옛 정보가 남는다.
 * 두 서비스가 같은 Redis를 쓰므로 여기서 해당 키를 직접 지워 즉시 반영시킨다.
 *
 * 키 규칙은 커뮤니티 서비스의 UserClient(CACHE_KEY_PREFIX, VISIBILITY_CACHE_KEY_PREFIX)와 맞물린
 * 서비스 간 계약이다 — 한쪽을 바꾸면 다른 쪽도 함께 바꿔야 한다.
 *
 * 삭제 실패는 무시한다(best-effort). 캐시를 못 지워도 TTL이 만료되면 결국 최신 값으로 수렴하므로,
 * 프로필 변경 자체를 실패시킬 이유가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityUserCacheEvictor {

    private static final String PROFILE_KEY_PREFIX = "community:user:profile:";
    private static final String VISIBILITY_KEY_PREFIX = "community:user:profile-public:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 트랜잭션 안에서 부르면 <b>커밋 이후에</b> 지운다.
     * 커밋 전에 지우면 그 사이에 커뮤니티가 조회해 옛 값을 다시 캐싱해버릴 수 있다.
     */
    public void evict(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            delete(userId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                delete(userId);
            }
        });
    }

    private void delete(UUID userId) {
        try {
            redisTemplate.delete(List.of(
                    PROFILE_KEY_PREFIX + userId,
                    VISIBILITY_KEY_PREFIX + userId
            ));
        } catch (Exception e) {
            log.warn("커뮤니티 사용자 캐시 무효화 실패 (userId={}): {}", userId, e.getMessage());
        }
    }
}
