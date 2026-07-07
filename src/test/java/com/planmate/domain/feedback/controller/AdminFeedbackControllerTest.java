package com.planmate.domain.feedback.controller;

import com.planmate.domain.feedback.entity.BetaFeedback;
import com.planmate.domain.feedback.repository.BetaFeedbackRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BetaFeedbackRepository betaFeedbackRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        User admin = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("admin-feedback-" + UUID.randomUUID() + "@example.com")
                .nickname("admin-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.ADMIN)
                .build());
        adminToken = jwtTokenProvider.createAccessToken(admin.getUserId(), admin.getRole().name());

        User user = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("user-feedback-" + UUID.randomUUID() + "@example.com")
                .nickname("user-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        userToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());

        betaFeedbackRepository.save(BetaFeedback.builder().content("관리자 조회 테스트용 피드백").build());
    }

    // ---------- GET /api/admin/feedbacks ----------

    @Test
    @DisplayName("피드백 전체 조회 성공 - ADMIN이면 실제 DB에 저장된 피드백을 전부 반환한다")
    void getFeedbacks_success_admin() throws Exception {
        mockMvc.perform(get("/api/admin/feedbacks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbacks[?(@.content == '관리자 조회 테스트용 피드백')]").exists());
    }

    @Test
    @DisplayName("피드백 전체 조회 실패 - 인증 헤더 없으면 401(COMMON_002)")
    void getFeedbacks_fail_noAuth() throws Exception {
        mockMvc.perform(get("/api/admin/feedbacks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("피드백 전체 조회 실패 - 로그인했지만 ADMIN이 아니면 403(COMMON_003)")
    void getFeedbacks_fail_notAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/feedbacks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }
}
