package com.planmate.config;

import com.sharedsync.shared.auth.WsAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// SharedSync의 WsAuthChannelInterceptor(@Component)는 STOMP CONNECT/SUBSCRIBE/SEND 단에서
// AuthenticationTokenResolver/StompAccessValidator를 호출하는 실제 인증·인가 로직을 담고 있지만,
// 프레임워크 어디에도 clientInboundChannel에 등록되어 있지 않다(SharedWebSocketConfig는 presence
// 하트비트 인터셉터만 등록) — 빈으로 존재할 뿐 실제로는 한 번도 호출되지 않는 상태였다(STOMP 통합
// 테스트로 재현 확인). Spring은 WebSocketMessageBrokerConfigurer 빈을 여러 개 허용하고 전부 합쳐서
// 적용하므로, 이 클래스만 추가하면 SharedWebSocketConfig와 충돌 없이 인터셉터가 실제로 걸린다.
@Configuration
@RequiredArgsConstructor
public class SharedSyncWebSocketAuthConfig implements WebSocketMessageBrokerConfigurer {

    private final WsAuthChannelInterceptor wsAuthChannelInterceptor;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(wsAuthChannelInterceptor);
    }
}
