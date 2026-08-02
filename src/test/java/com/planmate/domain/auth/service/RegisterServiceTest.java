package com.planmate.domain.auth.service;

import com.planmate.common.exception.domain.DuplicateEmailException;
import com.planmate.common.exception.domain.DuplicateNicknameException;
import com.planmate.common.exception.domain.InvalidTokenException;
import com.planmate.common.exception.domain.TokenExpiredException;
import com.planmate.domain.auth.dto.NicknameVerificationResponse;
import com.planmate.domain.auth.dto.RegisterRequest;
import com.planmate.domain.auth.dto.RegisterResponse;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import com.planmate.domain.user.internal.outbox.UserOutboxWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

    private static final String SIGNUP_TOKEN = "signup-token";
    private static final String EMAIL = "user@example.com";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserOutboxWriter userOutboxWriter;

    @InjectMocks
    private RegisterService registerService;

    private RegisterRequest validRequest() {
        return new RegisterRequest(SIGNUP_TOKEN, "nickname", "password1234", Gender.MALE, LocalDate.of(1998, 1, 1));
    }

    @Test
    @DisplayName("회원가입 성공 - User 생성, role은 항상 USER로 고정")
    void register_success() {
        given(jwtTokenProvider.extractVerifiedEmail(SIGNUP_TOKEN, EmailVerificationPurpose.SIGN_UP)).willReturn(EMAIL);
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(userRepository.existsByNickname("nickname")).willReturn(false);
        given(passwordEncoder.encode("password1234")).willReturn("encoded-password");

        RegisterResponse response = registerService.register(validRequest());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        assertThat(savedUser.getEmail()).isEqualTo(EMAIL);
        assertThat(savedUser.getProvider()).isEqualTo(UserProvider.LOCAL);
        assertThat(savedUser.getProviderId()).isNull();
        assertThat(savedUser.getPassword()).isEqualTo("encoded-password");
        assertThat(savedUser.getNickname()).isEqualTo("nickname");
        assertThat(savedUser.getGender()).isEqualTo(Gender.MALE);
        assertThat(savedUser.getBirthdate()).isEqualTo(LocalDate.of(1998, 1, 1));
        assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(response.userId()).isEqualTo(savedUser.getUserId());
    }

    @Test
    @DisplayName("회원가입 실패 - signupToken 무효/만료/purpose 불일치")
    void register_fail_invalidSignupToken() {
        given(jwtTokenProvider.extractVerifiedEmail(SIGNUP_TOKEN, EmailVerificationPurpose.SIGN_UP))
                .willThrow(new InvalidTokenException());

        assertThatThrownBy(() -> registerService.register(validRequest()))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("회원가입 실패 - signupToken 만료(무효와 별도 예외/에러코드로 전파됨 — 9장 표는 하나로 뭉쳐놨지만 실제로는 분리됨)")
    void register_fail_expiredSignupToken() {
        given(jwtTokenProvider.extractVerifiedEmail(SIGNUP_TOKEN, EmailVerificationPurpose.SIGN_UP))
                .willThrow(new TokenExpiredException());

        assertThatThrownBy(() -> registerService.register(validRequest()))
                .isInstanceOf(TokenExpiredException.class);

        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("회원가입 실패 - 이미 가입된 이메일(provider 무관)")
    void register_fail_duplicateEmail() {
        given(jwtTokenProvider.extractVerifiedEmail(SIGNUP_TOKEN, EmailVerificationPurpose.SIGN_UP)).willReturn(EMAIL);
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        assertThatThrownBy(() -> registerService.register(validRequest()))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userRepository, never()).existsByNickname(anyString());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("회원가입 실패 - 이미 사용 중인 닉네임")
    void register_fail_duplicateNickname() {
        given(jwtTokenProvider.extractVerifiedEmail(SIGNUP_TOKEN, EmailVerificationPurpose.SIGN_UP)).willReturn(EMAIL);
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(userRepository.existsByNickname("nickname")).willReturn(true);

        assertThatThrownBy(() -> registerService.register(validRequest()))
                .isInstanceOf(DuplicateNicknameException.class);

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("닉네임 중복확인 - 사용 가능")
    void verifyNickname_available() {
        given(userRepository.existsByNickname("nickname")).willReturn(false);

        NicknameVerificationResponse response = registerService.verifyNickname("nickname");

        assertThat(response.nicknameAvailable()).isTrue();
    }

    @Test
    @DisplayName("닉네임 중복확인 - 이미 사용 중")
    void verifyNickname_notAvailable() {
        given(userRepository.existsByNickname("nickname")).willReturn(true);

        NicknameVerificationResponse response = registerService.verifyNickname("nickname");

        assertThat(response.nicknameAvailable()).isFalse();
    }
}
