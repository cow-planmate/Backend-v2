package com.planmate.domain.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.auth.dto.NicknameVerificationRequest;
import com.planmate.domain.auth.dto.RegisterRequest;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
