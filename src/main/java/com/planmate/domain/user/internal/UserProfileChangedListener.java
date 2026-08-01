package com.planmate.domain.user.internal;

import com.planmate.domain.user.event.UserProfileChangedEvent;
import com.planmate.domain.user.internal.grpc.UserChangeStreamRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 프로필 변경을 구독 중인 커뮤니티 서비스에 알린다.
 *
 * <b>AFTER_COMMIT 이어야 한다.</b> 커밋 전에 알리면 커뮤니티가 그 사이에 조회해
 * 옛 값을 다시 캐싱해버린다.
 *
 * <b>@Async 인 이유:</b> 팬아웃은 네트워크 쓰기다. 요청 스레드에서 돌리면 느린 구독자가
 * 프로필 변경 응답을 붙잡는다. 알림이 늦거나 유실돼도 커뮤니티 캐시 TTL(10분/1분)로
 * 수렴하므로, 사용자 요청을 붙잡을 이유가 없다.
 *
 * DB를 건드리지 않으므로 NotificationEventListener 와 달리 @Transactional(REQUIRES_NEW)은
 * 필요 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileChangedListener {

    private final UserChangeStreamRegistry streamRegistry;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(UserProfileChangedEvent event) {
        try {
            streamRegistry.broadcast(event.userId());
        } catch (Exception e) {
            // best-effort — 알림에 실패해도 프로필 변경 자체는 이미 커밋됐고, TTL로 수렴한다
            log.warn("사용자 변경 알림 발행 실패 (userId={}): {}", event.userId(), e.getMessage());
        }
    }
}
