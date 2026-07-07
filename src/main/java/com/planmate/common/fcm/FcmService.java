package com.planmate.common.fcm;

import org.springframework.stereotype.Service;

import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;

import lombok.extern.slf4j.Slf4j;

/**
 * 순수 infra — User/UserRepository를 모른다. 무효 토큰 정리는 호출자(domain/notification)의 책임.
 * 호출자(NotificationEventListener.handle)가 이미 @Async 경계이므로 이 메서드 자체엔 @Async를 붙이지 않는다.
 */
@Slf4j
@Service
public class FcmService {

    public FcmSendResult sendNotification(String fcmToken, String title, String body) {
        if (fcmToken == null || fcmToken.isBlank()) {
            return FcmSendResult.SKIPPED_NO_TOKEN;
        }

        Message message = Message.builder()
                .setToken(fcmToken)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();

        try {
            FirebaseMessaging.getInstance().send(message);
            return FcmSendResult.SUCCESS;
        } catch (FirebaseMessagingException e) {
            log.warn("FCM 알림 전송 실패: {}", e.getMessage());
            boolean invalid = e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                    || e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT;
            return invalid ? FcmSendResult.INVALID_TOKEN : FcmSendResult.FAILED_OTHER;
        } catch (Exception e) {
            log.warn("FCM 알림 전송 실패: {}", e.getMessage());
            return FcmSendResult.FAILED_OTHER;
        }
    }
}
