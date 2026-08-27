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
import com.planmate.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager entityManager;

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
    @DisplayName("로그인 실패 - 실제 DB에 정지 상태로 저장된 계정은 비밀번호가 맞아도 403, AUTH_009")
    void login_fail_accountSuspended() throws Exception {
        testUser.suspend(java.time.LocalDateTime.now().plusDays(3), "약관 위반");
        userRepository.saveAndFlush(testUser);
        entityManager.clear();

        LoginRequest request = new LoginRequest(testUser.getEmail(), RAW_PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_009"));
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
    @DisplayName("Access Token 재발급 성공 - 로그인 이후 관리자로 승격되면 재발급 토큰에 새 역할이 즉시 반영됨(매 재발급마다 최신 role 재조회)")
    void reissue_success_reflectsRoleChangedSinceLogin() throws Exception {
        issuedRefreshToken = "test-refresh-token-" + UUID.randomUUID();
        refreshTokenRedisRepository.save(issuedRefreshToken, testUser.getUserId(), Duration.ofDays(1));

        // JdbcTemplate은 JPA 영속성 컨텍스트를 거치지 않는 raw SQL이라, @BeforeEach의 save()로 생긴
        // testUser의 INSERT가 아직 flush 안 된 상태면 이 UPDATE가 대상 행을 아예 못 찾는다 — 먼저 flush.
        entityManager.flush();

        // 로그인 이후(=이 refreshToken 발급 이후) 관리자로 승격된 상황을 실제 DB에 반영해서 재현
        // (앱에 role 변경 API가 없어 관리자 도구/DB 직접 변경으로 일어나는 시나리오를 흉내낸 것)
        jdbcTemplate.update("UPDATE users SET role = ? WHERE user_id = ?", "ADMIN", testUser.getUserId());

        // testUser가 이미 1차 캐시에 관리 상태(role=USER)로 남아있으면 reissue()의 findById가
        // 캐시된 객체를 그대로 반환해버리므로, 캐시를 비워 다음 조회가 실제로 DB에 SELECT를 태우게 한다
        // (그룹 A 인증/마이페이지에서 만난 것과 같은 종류의 flush/clear 순서 문제).
        entityManager.clear();

        String responseBody = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(issuedRefreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn().getResponse().getContentAsString();

        String newAccessToken = objectMapper.readTree(responseBody).get("accessToken").asText();
        assertThat(jwtTokenProvider.extractRole(newAccessToken)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Access Token 재발급 실패 - 로그인 이후 정지된 계정은 리프레시 토큰이 살아있어도 403, AUTH_009")
    void reissue_fail_accountSuspended() throws Exception {
        issuedRefreshToken = "test-refresh-token-" + UUID.randomUUID();
        refreshTokenRedisRepository.save(issuedRefreshToken, testUser.getUserId(), Duration.ofDays(1));

        entityManager.flush();
        testUser.suspend(java.time.LocalDateTime.now().plusDays(3), "약관 위반");
        userRepository.saveAndFlush(testUser);
        entityManager.clear();

        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(issuedRefreshToken))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_009"));
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
