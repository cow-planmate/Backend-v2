package com.planmate.common.fcm;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class FcmServiceTest {

    private final FcmService fcmService = new FcmService();

    private MockedStatic<FirebaseMessaging> firebaseMessagingStatic;
    private FirebaseMessaging firebaseMessaging;

    @BeforeEach
    void setUp() {
        firebaseMessaging = mock(FirebaseMessaging.class);
        firebaseMessagingStatic = mockStatic(FirebaseMessaging.class);
        firebaseMessagingStatic.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
    }

    @AfterEach
    void tearDown() {
        firebaseMessagingStatic.close();
    }

    @Test
    @DisplayName("토큰이 null/blank면 발송 자체를 스킵한다")
    void sendNotification_noToken_skipped() {
        assertThat(fcmService.sendNotification(null, "title", "body")).isEqualTo(FcmSendResult.SKIPPED_NO_TOKEN);
        assertThat(fcmService.sendNotification("  ", "title", "body")).isEqualTo(FcmSendResult.SKIPPED_NO_TOKEN);
    }

    @Test
    @DisplayName("발송 성공 시 SUCCESS를 반환한다")
    void sendNotification_success() throws Exception {
        given(firebaseMessaging.send(any(Message.class))).willReturn("message-id");

        assertThat(fcmService.sendNotification("valid-token", "title", "body")).isEqualTo(FcmSendResult.SUCCESS);
    }

    @Test
    @DisplayName("UNREGISTERED 에러(앱 삭제 등)면 INVALID_TOKEN을 반환해 호출자가 토큰을 정리할 수 있게 한다")
    void sendNotification_unregisteredToken_returnsInvalidToken() throws Exception {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.UNREGISTERED);
        given(exception.getMessage()).willReturn("unregistered");
        given(firebaseMessaging.send(any(Message.class))).willThrow(exception);

        assertThat(fcmService.sendNotification("dead-token", "title", "body")).isEqualTo(FcmSendResult.INVALID_TOKEN);
    }

    @Test
    @DisplayName("INVALID_ARGUMENT 에러도 INVALID_TOKEN을 반환한다")
    void sendNotification_invalidArgument_returnsInvalidToken() throws Exception {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.INVALID_ARGUMENT);
        given(exception.getMessage()).willReturn("invalid argument");
        given(firebaseMessaging.send(any(Message.class))).willThrow(exception);

        assertThat(fcmService.sendNotification("bad-token", "title", "body")).isEqualTo(FcmSendResult.INVALID_TOKEN);
    }

    @Test
    @DisplayName("그 외 FirebaseMessagingException(네트워크/일시 오류 등)은 FAILED_OTHER를 반환하고 토큰은 건드리지 않는다")
    void sendNotification_otherFirebaseError_returnsFailedOther() throws Exception {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.INTERNAL);
        given(exception.getMessage()).willReturn("internal error");
        given(firebaseMessaging.send(any(Message.class))).willThrow(exception);

        assertThat(fcmService.sendNotification("token", "title", "body")).isEqualTo(FcmSendResult.FAILED_OTHER);
    }

    @Test
    @DisplayName("Firebase 미설정(FirebaseApp 미초기화) 환경에서도 예외를 삼키고 FAILED_OTHER를 반환한다 — 앱이 죽으면 안 됨(설계 문서 7절 4번)")
    void sendNotification_firebaseNotInitialized_doesNotThrow() {
        firebaseMessagingStatic.when(FirebaseMessaging::getInstance)
                .thenThrow(new IllegalStateException("FirebaseApp not initialized"));

        assertThatCode(() -> {
            FcmSendResult result = fcmService.sendNotification("token", "title", "body");
            assertThat(result).isEqualTo(FcmSendResult.FAILED_OTHER);
        }).doesNotThrowAnyException();
    }
}
