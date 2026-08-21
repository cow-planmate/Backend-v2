package com.planmate.domain.notification;

import com.planmate.common.fcm.FcmService;
import com.planmate.common.sse.SseEmitterService;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerCutoverTest {

    @Mock
    private FcmService fcmService;
    @Mock
    private SseEmitterService sseEmitterService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NotificationEventListener listener;

    @Test
    @DisplayName("Notification 전달을 켠 뒤에는 Backend-v2가 같은 알림을 FCM/SSE로 다시 보내지 않는다")
    void disabledLegacyDeliverySkipsEveryLocalDelivery() {
        ReflectionTestUtils.setField(listener, "legacyDeliveryEnabled", false);

        listener.handle(new CollaborationNotificationEvent(
                UUID.randomUUID(), "invitation", Map.of("type", "INVITE"), "일정 초대", "초대했습니다."));

        verifyNoInteractions(userRepository, fcmService, sseEmitterService);
    }
}
