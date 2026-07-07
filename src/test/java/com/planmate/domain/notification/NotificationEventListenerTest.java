package com.planmate.domain.notification;

import com.planmate.common.fcm.FcmSendResult;
import com.planmate.common.fcm.FcmService;
import com.planmate.common.sse.SseEmitterService;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 클래스 레벨 @Transactional을 일부러 안 쓴다 — 붙이면 테스트 트랜잭션이 끝까지 롤백되어
 * AFTER_COMMIT 리스너가 영원히 안 불린다(설계 문서 7절 2번이 경고한 함정 그대로).
 * 대신 TransactionTemplate으로 실제 커밋/롤백을 직접 제어해 AFTER_COMMIT의 두 갈래
 * 동작(커밋 시 발동 / 롤백 시 미발동)을 모두 검증한다.
 */
@SpringBootTest
class NotificationEventListenerTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private FcmService fcmService;
    @MockitoBean
    private SseEmitterService sseEmitterService;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("notification-listener-" + UUID.randomUUID() + "@example.com")
                .nickname("notif-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .fcmToken("old-token")
                .build());
    }

    @AfterEach
    void tearDown() {
        userRepository.delete(user);
    }

    @Test
    @DisplayName("커밋 후 무효 토큰이면 토큰을 정리하고 SSE도 함께 보낸다")
    void handle_afterCommit_invalidToken_cleansTokenAndSendsSse() {
        given(fcmService.sendNotification(eq("old-token"), eq("title"), eq("body")))
                .willReturn(FcmSendResult.INVALID_TOKEN);

        UUID userId = user.getUserId();
        Map<String, String> ssePayload = Map.of("k", "v");

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new CollaborationNotificationEvent(
                        userId, "invitation", ssePayload, "title", "body")));

        // handle()은 @Async라 별도 스레드에서 돈다. SSE 호출이 handle() 안에서 가장 마지막에 실행되므로
        // 이 호출을 기다리면 그 앞의 REQUIRES_NEW 토큰 정리 트랜잭션은 이미 커밋된 뒤다.
        verify(sseEmitterService, timeout(3000)).sendNotification(eq(userId), eq("invitation"), eq(ssePayload));

        User updated = userRepository.findById(userId).orElseThrow();
        assertThat(updated.getFcmToken()).isNull();
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 커밋 전 이벤트는 리스너가 아예 호출하지 않는다 — v1(동기 호출) 대비 개선 포인트")
    void handle_rolledBackTransaction_listenerNeverInvoked() {
        UUID userId = user.getUserId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new CollaborationNotificationEvent(
                    userId, "invitation", Map.of(), "title", "body"));
            status.setRollbackOnly();
        });

        verify(sseEmitterService, timeout(1000).times(0)).sendNotification(any(), any(), any());
        verify(fcmService, timeout(100).times(0)).sendNotification(any(), any(), any());
    }
}
