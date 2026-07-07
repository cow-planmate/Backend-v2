package com.planmate.domain.notification;

import java.util.UUID;

/**
 * SSE(eventName/data)와 FCM(fcmTitle/fcmBody)의 페이로드가 서로 달라 5개 필드 모두 필요하다.
 * userId 등 식별자/primitive만 담는다 — 엔티티를 담으면 @Async 리스너에서 LazyInitializationException 위험.
 */
public record CollaborationNotificationEvent(
        UUID userId,
        String eventName,
        Object data,
        String fcmTitle,
        String fcmBody
) {
}
