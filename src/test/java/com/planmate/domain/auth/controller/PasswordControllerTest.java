package com.planmate.domain.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.auth.dto.ChangePasswordRequest;
import com.planmate.domain.auth.dto.SendTempPasswordRequest;
import com.planmate.domain.auth.dto.VerifyPasswordRequest;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordControllerTest {

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
    private JwtTokenProvider jwtTokenProvider;

    private User testUser;
    private String accessToken;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(User.builder()
                .provider(UserProvider.LOCAL)
                .email("password-test-" + UUID.randomUUID() + "@example.com")
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .nickname("password-test-" + UUID.randomUUID())
                .role(UserRole.USER)
                .build());
        accessToken = jwtTokenProvider.createAccessToken(testUser.getUserId(), testUser.getRole().name());
    }

    @Test
    @DisplayName("현재 비밀번호 확인 성공 - 204")
    void verifyPassword_success() throws Exception {
        mockMvc.perform(post("/api/auth/password/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyPasswordRequest(RAW_PASSWORD))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("현재 비밀번호 확인 실패 - 비밀번호 불일치 시 401, AUTH_008")
    void verifyPassword_fail_passwordMismatch() throws Exception {
        mockMvc.perform(post("/api/auth/password/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyPasswordRequest("wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_008"));
    }

    @Test
    @DisplayName("현재 비밀번호 확인 실패 - 인증 토큰 없으면 401")
    void verifyPassword_fail_noAuth() throws Exception {
        mockMvc.perform(post("/api/auth/password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyPasswordRequest(RAW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비밀번호 변경 성공 - 204, DB에 새 비밀번호 실제 반영 확인")
    void changePassword_success() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest(RAW_PASSWORD, "newPassword1234", "newPassword1234");

        mockMvc.perform(patch("/api/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        User updated = userRepository.findById(testUser.getUserId()).orElseThrow();
        assertThat(passwordEncoder.matches("newPassword1234", updated.getPassword())).isTrue();
    }

    @Test
    @DisplayName("비밀번호 변경 검증 실패 - 새 비밀번호와 확인이 다르면 400, COMMON_001")
    void changePassword_validationFailure_newPasswordMismatch() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest(RAW_PASSWORD, "newPassword1234", "differentPassword1234");

        mockMvc.perform(patch("/api/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("비밀번호 변경 실패 - 현재 비밀번호 불일치 시 401, AUTH_008")
    void changePassword_fail_currentPasswordMismatch() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("wrong-current-password", "newPassword1234", "newPassword1234");

        mockMvc.perform(patch("/api/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_008"));
    }

    @Test
    @DisplayName("임시 비밀번호 발송 성공 - 204, 계정 비밀번호가 실제로 교체됨(인증 불필요)")
    void sendTempPassword_success() throws Exception {
        String verificationToken = jwtTokenProvider.createEmailVerificationToken(testUser.getEmail(), EmailVerificationPurpose.RESET_PASSWORD);

        mockMvc.perform(post("/api/auth/password/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendTempPasswordRequest(verificationToken))))
                .andExpect(status().isNoContent());

        User updated = userRepository.findById(testUser.getUserId()).orElseThrow();
        assertThat(passwordEncoder.matches(RAW_PASSWORD, updated.getPassword())).isFalse();
    }

    @Test
    @DisplayName("임시 비밀번호 발송 실패 - verificationToken이 유효하지 않으면 401, AUTH_001")
    void sendTempPassword_fail_invalidToken() throws Exception {
        mockMvc.perform(post("/api/auth/password/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendTempPasswordRequest("not-a-valid-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test
    @DisplayName("임시 비밀번호 발송 실패 - verificationToken이 실제로 만료되면 401, AUTH_002(무효 토큰과 다른 코드)")
    void sendTempPassword_fail_expiredToken() throws Exception {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(jwtTokenProvider, "key");
        String expiredToken = Jwts.builder()
                .subject(testUser.getEmail())
                .claim("purpose", EmailVerificationPurpose.RESET_PASSWORD.name())
                .issuedAt(Date.from(Instant.now().minusSeconds(700)))
                .expiration(Date.from(Instant.now().minusSeconds(100)))
                .signWith(key)
                .compact();

        mockMvc.perform(post("/api/auth/password/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendTempPasswordRequest(expiredToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_002"));
    }
}
