package com.planmate.common.sse;

import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 구독의 인증 계약. 브라우저 EventSource 는 커스텀 헤더를 못 붙이므로 쿼리 파라미터 token
 * 폴백이 필요한데, 그 폴백은 이제 게이트웨이가 처리한다 — 게이트웨이가 token 을 검증한 뒤
 * X-User-Id/X-User-Role 로 바꿔 넣어주므로 이 서비스는 신원 헤더만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SseControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User user;
    private String accessToken;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("sse-controller-" + UUID.randomUUID() + "@example.com")
                .nickname("sse-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        accessToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());
    }

    @AfterEach
    void tearDown() {
        userRepository.delete(user);
    }

    // 이 SseEmitter는 30분 타임아웃 전엔 스스로 안 끝나므로 asyncDispatch()로 완료를 기다리면 테스트가
    // 영원히 멈춘다(직접 재현·확인함). SseEmitterService.subscribe()가 emitter를 만들어 반환하기 "전에"
    // connect 이벤트를 emitter.send()로 이미 큐잉해두고, 그 큐는 Spring이 반환값을 초기화하는 시점(같은
    // 요청 스레드 안)에 바로 flush되므로, 최초 perform() 응답 버퍼에 이미 connect 이벤트가 들어있다 —
    // asyncDispatch 없이 request().asyncStarted() 확인 + 응답 바디 직접 조회로 충분하다.
    @Test
    @Timeout(10)
    @DisplayName("게이트웨이 신원 헤더로 구독하면 connect 이벤트를 받는다")
    void subscribe_withIdentityHeaders_succeeds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sse/subscribe")
                        .header("X-User-Id", user.getUserId().toString())
                        .header("X-User-Role", user.getRole().name()))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("connect");
    }

    @Test
    @DisplayName("신원 헤더가 없으면 401")
    void subscribe_noIdentityHeaders_unauthorized() throws Exception {
        mockMvc.perform(get("/api/sse/subscribe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("쿼리 파라미터 token 은 더 이상 이 서비스에서 인증되지 않는다 — 게이트웨이가 변환한다")
    void subscribe_withQueryParamTokenOnly_unauthorized() throws Exception {
        mockMvc.perform(get("/api/sse/subscribe").param("token", accessToken))
                .andExpect(status().isUnauthorized());
    }
}
