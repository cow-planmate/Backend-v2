package com.planmate.domain.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.auth.dto.LoginRequest;
import com.planmate.domain.auth.dto.LogoutRequest;
import com.planmate.domain.auth.dto.RefreshTokenRequest;
import com.planmate.domain.auth.repository.RefreshTokenRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RefreshTokenRedisRepository refreshTokenRedisRepository;

    private User testUser;
    private String issuedRefreshToken;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(User.builder()
                .provider(UserProvider.LOCAL)
                .email("auth-test-" + UUID.randomUUID() + "@example.com")
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .nickname("auth-test-" + UUID.randomUUID())
                .role(UserRole.USER)
                .build());
    }

    @AfterEach
    void tearDown() {
        // DB 쓰기는 @Transactional로 롤백되지만 Redis는 같은 트랜잭션에 묶이지 않으므로 직접 정리
        if (issuedRefreshToken != null) {
            refreshTokenRedisRepository.delete(issuedRefreshToken);
        }
    }

    @Test
    @DisplayName("로그인 성공 - 200, 토큰 발급, Redis에 Refresh Token 저장")
    void login_success() throws Exception {
        LoginRequest request = new LoginRequest(testUser.getEmail(), RAW_PASSWORD);

        String responseBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.userId").value(testUser.getUserId().toString()))
                .andExpect(jsonPath("$.nickname").value(testUser.getNickname()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn().getResponse().getContentAsString();

        issuedRefreshToken = objectMapper.readTree(responseBody).get("refreshToken").asText();
        assertThat(refreshTokenRedisRepository.findUserId(issuedRefreshToken)).contains(testUser.getUserId());
    }

    @Test
    @DisplayName("로그인 검증 실패 - 이메일 형식 위반 시 400, COMMON_001")
    void login_validationFailure_invalidEmail() throws Exception {
        LoginRequest request = new LoginRequest("not-an-email", RAW_PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("로그아웃 - Refresh Token 있으면 204 + Redis에서 삭제")
    void logout_withToken_returnsNoContentAndDeletesToken() throws Exception {
        String refreshToken = "test-refresh-token-" + UUID.randomUUID();
        refreshTokenRedisRepository.save(refreshToken, testUser.getUserId(), Duration.ofDays(1));

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken))))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenRedisRepository.findUserId(refreshToken)).isEmpty();
    }

    @Test
    @DisplayName("로그아웃 - 빈 토큰도 관대하게 204 처리")
    void logout_blankToken_stillReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(""))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Access Token 재발급 성공 - 200, 새 Access Token 발급")
    void reissue_success() throws Exception {
        issuedRefreshToken = "test-refresh-token-" + UUID.randomUUID();
        refreshTokenRedisRepository.save(issuedRefreshToken, testUser.getUserId(), Duration.ofDays(1));

        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(issuedRefreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    @DisplayName("Access Token 재발급 실패 - Redis에 없는 토큰이면 401, AUTH_001")
    void reissue_invalidToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("nonexistent-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }
}
