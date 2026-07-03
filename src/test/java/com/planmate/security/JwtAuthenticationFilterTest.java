package com.planmate.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.auth.dto.VerifyPasswordRequest;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JwtAuthenticationFilterTest {

    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("이메일 인증 토큰(subject=email)을 보호된 엔드포인트에 Bearer로 보내면 크래시 없이 401")
    void emailVerificationTokenAsBearerToken_returnsCleanUnauthorized_notCrash() throws Exception {
        String emailVerificationToken = jwtTokenProvider.createEmailVerificationToken("someone@example.com", EmailVerificationPurpose.SIGN_UP);

        mockMvc.perform(post("/api/auth/password/verify")
                        .header("Authorization", "Bearer " + emailVerificationToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyPasswordRequest("anything"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Refresh Token을 보호된 엔드포인트에 Bearer로 보내면 401 (Access Token 대신 쓰일 수 없어야 함)")
    void refreshTokenAsBearerToken_returnsUnauthorized() throws Exception {
        User user = userRepository.save(User.builder()
                .provider(UserProvider.LOCAL)
                .email("jwt-filter-test-" + UUID.randomUUID() + "@example.com")
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .nickname("jwt-filter-test")
                .role(UserRole.USER)
                .build());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserId());

        mockMvc.perform(post("/api/auth/password/verify")
                        .header("Authorization", "Bearer " + refreshToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyPasswordRequest(RAW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정상 Access Token은 그대로 인증 통과")
    void accessToken_stillAuthenticatesNormally() throws Exception {
        User user = userRepository.save(User.builder()
                .provider(UserProvider.LOCAL)
                .email("jwt-filter-test-" + UUID.randomUUID() + "@example.com")
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .nickname("jwt-filter-test")
                .role(UserRole.USER)
                .build());
        String accessToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());

        mockMvc.perform(post("/api/auth/password/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyPasswordRequest(RAW_PASSWORD))))
                .andExpect(status().isNoContent());
    }
}
