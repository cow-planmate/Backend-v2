package com.planmate.domain.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.mail.MailService;
import com.planmate.domain.auth.dto.EmailVerificationConfirmRequest;
import com.planmate.domain.auth.dto.EmailVerificationSendRequest;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.auth.repository.EmailVerificationRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmailVerificationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailVerificationRedisRepository emailVerificationRedisRepository;
    @MockitoBean
    private MailService mailService;

    private String signUpEmail;

    @AfterEach
    void tearDown() {
        // DB 쓰기는 @Transactional로 롤백되지만 Redis는 같은 트랜잭션에 묶이지 않으므로 직접 정리
        if (signUpEmail != null) {
            emailVerificationRedisRepository.delete(signUpEmail, EmailVerificationPurpose.SIGN_UP);
        }
    }

    @Test
    @DisplayName("인증코드 발송 성공 - SIGN_UP, 미가입 이메일이면 204 + Redis에 코드 저장(실제 메일 발송)")
    void sendVerificationCode_signUp_success() throws Exception {
        signUpEmail = "email-verify-test-" + UUID.randomUUID() + "@example.com";
        EmailVerificationSendRequest request = new EmailVerificationSendRequest(signUpEmail, EmailVerificationPurpose.SIGN_UP);

        mockMvc.perform(post("/api/auth/email/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        assertThat(emailVerificationRedisRepository.findCode(signUpEmail, EmailVerificationPurpose.SIGN_UP)).isPresent();
    }

    @Test
    @DisplayName("인증코드 발송 검증 실패 - 이메일 형식 위반 시 400, COMMON_001")
    void sendVerificationCode_validationFailure_invalidEmail() throws Exception {
        EmailVerificationSendRequest request = new EmailVerificationSendRequest("not-an-email", EmailVerificationPurpose.SIGN_UP);

        mockMvc.perform(post("/api/auth/email/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("인증코드 발송 실패 - SIGN_UP인데 이미 가입된 이메일이면 409, AUTH_004(계정 존재 노출)")
    void sendVerificationCode_signUp_duplicateEmail_returnsConflict() throws Exception {
        String existingEmail = "email-verify-existing-" + UUID.randomUUID() + "@example.com";
        userRepository.save(User.builder()
                .provider(UserProvider.LOCAL)
                .email(existingEmail)
                .password("encoded-password")
                .nickname("nick-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());

        EmailVerificationSendRequest request = new EmailVerificationSendRequest(existingEmail, EmailVerificationPurpose.SIGN_UP);

        mockMvc.perform(post("/api/auth/email/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_004"));

        assertThat(emailVerificationRedisRepository.findCode(existingEmail, EmailVerificationPurpose.SIGN_UP)).isEmpty();
    }

    @Test
    @DisplayName("인증코드 발송 - RESET_PASSWORD인데 미가입 이메일이어도 타이밍 사이드채널 방지를 위해 동일하게 204 + 코드 저장")
    void sendVerificationCode_resetPassword_emailNotFound_stillSendsCode() throws Exception {
        String notFoundEmail = "email-verify-notfound-" + UUID.randomUUID() + "@example.com";
        EmailVerificationSendRequest request = new EmailVerificationSendRequest(notFoundEmail, EmailVerificationPurpose.RESET_PASSWORD);

        try {
            mockMvc.perform(post("/api/auth/email/verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNoContent());

            assertThat(emailVerificationRedisRepository.findCode(notFoundEmail, EmailVerificationPurpose.RESET_PASSWORD)).isPresent();
        } finally {
            emailVerificationRedisRepository.delete(notFoundEmail, EmailVerificationPurpose.RESET_PASSWORD);
        }
    }

    @Test
    @DisplayName("인증코드 확인 성공 - 발송된 코드와 일치하면 200 + verificationToken 반환, Redis에서 삭제")
    void confirmVerificationCode_success() throws Exception {
        signUpEmail = "email-verify-confirm-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/email/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailVerificationSendRequest(signUpEmail, EmailVerificationPurpose.SIGN_UP))))
                .andExpect(status().isNoContent());

        int code = emailVerificationRedisRepository.findCode(signUpEmail, EmailVerificationPurpose.SIGN_UP).orElseThrow();

        EmailVerificationConfirmRequest confirmRequest = new EmailVerificationConfirmRequest(signUpEmail, EmailVerificationPurpose.SIGN_UP, code);

        mockMvc.perform(post("/api/auth/email/verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationToken").exists());

        assertThat(emailVerificationRedisRepository.findCode(signUpEmail, EmailVerificationPurpose.SIGN_UP)).isEmpty();
    }

    @Test
    @DisplayName("인증코드 확인 검증 실패 - verificationCode 누락 시 400, COMMON_001")
    void confirmVerificationCode_validationFailure_missingCode() throws Exception {
        String requestBody = """
                {"email":"user@example.com","purpose":"SIGN_UP"}
                """;

        mockMvc.perform(post("/api/auth/email/verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("인증코드 확인 실패 - Redis에 인증 요청이 없으면(만료/미발송) 401, AUTH_006")
    void confirmVerificationCode_codeNotFound_returnsUnauthorized() throws Exception {
        String neverSentEmail = "email-verify-never-sent-" + UUID.randomUUID() + "@example.com";
        EmailVerificationConfirmRequest request = new EmailVerificationConfirmRequest(neverSentEmail, EmailVerificationPurpose.SIGN_UP, 123456);

        mockMvc.perform(post("/api/auth/email/verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_006"));
    }

    @Test
    @DisplayName("인증코드 확인 실패 - 코드 불일치 시 401, AUTH_007")
    void confirmVerificationCode_codeMismatch_returnsUnauthorized() throws Exception {
        signUpEmail = "email-verify-mismatch-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/email/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailVerificationSendRequest(signUpEmail, EmailVerificationPurpose.SIGN_UP))))
                .andExpect(status().isNoContent());

        int actualCode = emailVerificationRedisRepository.findCode(signUpEmail, EmailVerificationPurpose.SIGN_UP).orElseThrow();
        int wrongCode = actualCode == 100000 ? actualCode + 1 : actualCode - 1;

        EmailVerificationConfirmRequest confirmRequest = new EmailVerificationConfirmRequest(signUpEmail, EmailVerificationPurpose.SIGN_UP, wrongCode);

        mockMvc.perform(post("/api/auth/email/verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_007"));
    }
}
