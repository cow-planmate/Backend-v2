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
 * 사용자 프로필이 바뀌었음을 커뮤니티 서비스에 알린다.
 *
 * 커뮤니티는 게시글·댓글 작성자 정보를 받아 Redis에 10분간 캐싱하는데, 그대로 두면
 * 프로필 사진이나 닉네임을 바꿔도 남의 피드·댓글에 최대 10분간 옛 정보가 남는다.
 *
 * <p><b>알림 경로가 두 개인 이유(전환기)</b><br>
 * 원래는 두 서비스가 같은 Redis를 쓴다는 점을 이용해 커뮤니티 소유 키를 여기서 직접 지웠다.
 * 이제는 gRPC WatchUserChanges 스트림으로 알리고 커뮤니티가 <b>자기</b> 캐시를 지운다 —
 * 그래야 Redis를 분리할 수 있다. 다만 Redis 분리 전까지는 아직 스트림을 구독하지 않는
 * 구버전 커뮤니티가 떠 있을 수 있으므로 <b>기존 직접 삭제도 함께</b> 유지한다.
 * Redis 분리 시점에 {@link #deleteSharedRedisKeys} 와 그 상수들을 제거할 것.
 *
 * <p>실패는 모두 무시한다(best-effort). 알림을 못 보내도 TTL이 만료되면 결국 최신 값으로
 * 수렴하므로, 프로필 변경 자체를 실패시킬 이유가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserChangeNotifier {

    // 전환기 전용 — 커뮤니티 UserClient의 캐시 키와 맞물린 계약이다. Redis 분리 시 함께 제거.
    private static final String PROFILE_KEY_PREFIX = "community:user:profile:";
    private static final String VISIBILITY_KEY_PREFIX = "community:user:profile-public:";

    private final StringRedisTemplate redisTemplate;
    private final UserChangePublisher publisher;

    /**
     * 트랜잭션 안에서 부르면 <b>커밋 이후에</b> 알린다.
     * 커밋 전에 알리면 그 사이에 커뮤니티가 조회해 옛 값을 다시 캐싱해버릴 수 있다.
     */
    public void notifyChanged(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(userId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(userId);
            }
        });
    }

    private void dispatch(UUID userId) {
        try {
            publisher.publish(userId);
        } catch (Exception e) {
            log.warn("사용자 변경 알림 발행 실패 (userId={}): {}", userId, e.getMessage());
        }
        deleteSharedRedisKeys(userId);
    }

    /** @deprecated Redis 분리와 함께 제거한다. {@link UserChangeNotifier} 클래스 주석 참고. */
    @Deprecated(forRemoval = true)
    private void deleteSharedRedisKeys(UUID userId) {
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
