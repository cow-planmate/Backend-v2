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
 * 설계 문서(04_기능/실시간_알림/A_구독.md) 1장 발견사항 1 — 브라우저 네이티브 EventSource는
 * 커스텀 헤더를 못 붙이므로 /api/sse/ 경로에 한해 쿼리 파라미터 token 폴백이 필요하다.
 * 이 테스트는 그 폴백이 실제로 동작하는지, 그리고 다른 API로 새지 않는지(필터는 전역 공용 파일)를 함께 검증한다.
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
    @DisplayName("Authorization 헤더로 구독하면 connect 이벤트를 받는다")
    void subscribe_withHeader_succeeds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sse/subscribe")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("connect");
    }

    @Test
    @Timeout(10)
    @DisplayName("헤더 없이 쿼리 파라미터 token만으로도 구독된다 — 브라우저 EventSource 대응")
    void subscribe_withQueryParamToken_succeeds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sse/subscribe").param("token", accessToken))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("connect");
    }

    @Test
    @DisplayName("헤더도 쿼리 파라미터도 없으면 401")
    void subscribe_noToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/sse/subscribe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SSE가 아닌 다른 API는 쿼리 파라미터 token 폴백이 적용되지 않는다 — 필터 수정 범위가 /api/sse/로 한정됐는지 확인")
    void nonSseEndpoint_queryParamTokenNotAccepted() throws Exception {
        mockMvc.perform(get("/api/user/profile").param("token", accessToken))
                .andExpect(status().isUnauthorized());
    }
}
