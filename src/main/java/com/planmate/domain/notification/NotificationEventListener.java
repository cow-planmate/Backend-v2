package com.planmate.domain.notification;

import com.planmate.common.fcm.FcmSendResult;
import com.planmate.common.fcm.FcmService;
import com.planmate.common.sse.SseEmitterService;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * common/fcm·common/sse는 User/UserRepository를 모르는 순수 infra로 남기고,
 * 무효 FCM 토큰 정리(도메인 변경)는 이 domain 계층 리스너가 수행한다.
 *
 * AFTER_COMMIT은 그 자체로 트랜잭션을 열어주지 않으므로, handle()에 @Transactional이 없으면
 * changeFcmToken(null) 변경 감지가 동작하지 않아 DB에 조용히 반영되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final FcmService fcmService;
    private final SseEmitterService sseEmitterService;
    private final UserRepository userRepository;

    // AFTER_COMMIT 시점엔 원래 트랜잭션이 이미 끝나 있어 REQUIRED(기본값)로는 참여할 트랜잭션이 없다.
    // Spring이 이 조합 자체를 기동 시점에 막아두므로(RestrictedTransactionalEventListenerFactory) REQUIRES_NEW 필수.
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(CollaborationNotificationEvent event) {
        User receiver = userRepository.findById(event.userId()).orElse(null);
        if (receiver != null) {
            FcmSendResult result = fcmService.sendNotification(receiver.getFcmToken(), event.fcmTitle(), event.fcmBody());
            if (result == FcmSendResult.INVALID_TOKEN) {
                try {
                    receiver.changeFcmToken(null);
                } catch (Exception cleanupEx) {
                    log.warn("FCM 무효 토큰 정리 실패 (userId: {}): {}", event.userId(), cleanupEx.getMessage());
                }
            }
        }
        sseEmitterService.sendNotification(event.userId(), event.eventName(), event.data());
    }
}
