package com.planmate.domain.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.auth.dto.NicknameVerificationRequest;
import com.planmate.domain.auth.dto.RegisterRequest;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegisterControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("회원가입 성공 - 201, User 실제 저장 확인")
    void register_success() throws Exception {
        String email = "register-test-" + UUID.randomUUID() + "@example.com";
        String signupToken = jwtTokenProvider.createEmailVerificationToken(email, EmailVerificationPurpose.SIGN_UP);
        RegisterRequest request = new RegisterRequest(
                signupToken, "nick-" + UUID.randomUUID().toString().substring(0, 8),
                "password1234", Gender.FEMALE, LocalDate.of(1999, 5, 20));

        String responseBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").exists())
                .andReturn().getResponse().getContentAsString();

        UUID userId = UUID.fromString(objectMapper.readTree(responseBody).get("userId").asText());
        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(userRepository.findById(userId).get().getEmail()).isEqualTo(email);
    }

    @Test
    @DisplayName("회원가입 실패 - 실제 DB에 이미 같은 이메일로 가입된 계정이 있으면 409, AUTH_004")
    void register_fail_duplicateEmail_realDb() throws Exception {
        String email = "dup-email-" + UUID.randomUUID() + "@example.com";
        userRepository.save(User.builder()
                .provider(UserProvider.LOCAL)
                .email(email)
                .password("encoded-password")
                .nickname("existing-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());

        String signupToken = jwtTokenProvider.createEmailVerificationToken(email, EmailVerificationPurpose.SIGN_UP);
        RegisterRequest request = new RegisterRequest(
                signupToken, "newnick-" + UUID.randomUUID().toString().substring(0, 8),
                "password1234", Gender.FEMALE, LocalDate.of(1999, 5, 20));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
    }

    @Test
    @DisplayName("회원가입 실패 - 실제 DB에 이미 같은 닉네임을 쓰는 계정이 있으면 409, AUTH_005")
    void register_fail_duplicateNickname_realDb() throws Exception {
        String takenNickname = "taken-" + UUID.randomUUID().toString().substring(0, 8);
        userRepository.save(User.builder()
                .provider(UserProvider.LOCAL)
                .email("existing-" + UUID.randomUUID() + "@example.com")
                .password("encoded-password")
                .nickname(takenNickname)
                .role(UserRole.USER)
                .build());

        String email = "register-test-" + UUID.randomUUID() + "@example.com";
        String signupToken = jwtTokenProvider.createEmailVerificationToken(email, EmailVerificationPurpose.SIGN_UP);
        RegisterRequest request = new RegisterRequest(
                signupToken, takenNickname, "password1234", Gender.FEMALE, LocalDate.of(1999, 5, 20));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_005"));
    }

    @Test
    @DisplayName("회원가입 실패 - signupToken이 실제로 만료되면 401, AUTH_002(설계문서는 AUTH_001로 뭉뚱그려놨지만 실제 코드는 별도 응답)")
    void register_fail_expiredSignupToken_realToken() throws Exception {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(jwtTokenProvider, "key");
        String expiredToken = Jwts.builder()
                .subject("expired-" + UUID.randomUUID() + "@example.com")
                .claim("purpose", EmailVerificationPurpose.SIGN_UP.name())
                .issuedAt(Date.from(Instant.now().minusSeconds(700)))
                .expiration(Date.from(Instant.now().minusSeconds(100)))
                .signWith(key)
                .compact();

        RegisterRequest request = new RegisterRequest(
                expiredToken, "nick-" + UUID.randomUUID().toString().substring(0, 8),
                "password1234", Gender.FEMALE, LocalDate.of(1999, 5, 20));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_002"));
    }

    @Test
    @DisplayName("회원가입 검증 실패 - signupToken 빈 값이면 400, COMMON_001")
    void register_validationFailure_blankSignupToken() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "", "nickname", "password1234", Gender.MALE, LocalDate.of(1999, 5, 20));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("닉네임 중복확인 성공 - 200, 사용 가능")
    void verifyNickname_available() throws Exception {
        NicknameVerificationRequest request = new NicknameVerificationRequest("nick-" + UUID.randomUUID());

        mockMvc.perform(post("/api/auth/register/nickname/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nicknameAvailable").value(true));
    }

    @Test
    @DisplayName("닉네임 중복확인 검증 실패 - 빈 값이면 400, COMMON_001")
    void verifyNickname_validationFailure_blank() throws Exception {
        NicknameVerificationRequest request = new NicknameVerificationRequest("");

        mockMvc.perform(post("/api/auth/register/nickname/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }
}
