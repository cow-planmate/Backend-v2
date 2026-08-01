package com.planmate.common.internal;

import java.util.UUID;

/**
 * 사용자 프로필 변경을 내부 구독자(커뮤니티)에게 알린다.
 *
 * 도메인 코드가 gRPC에 직접 의존하지 않도록 두는 경계다 —
 * 구현체는 common.internal.grpc 패키지에 있다.
 */
public interface UserChangePublisher {

    /** 구독자가 없거나 발행에 실패해도 예외를 던지지 않는다(best-effort). */
    void publish(UUID userId);
}
