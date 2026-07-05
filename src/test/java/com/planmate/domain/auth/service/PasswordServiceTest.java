package com.planmate.domain.auth.service;

import com.planmate.common.exception.domain.InvalidTokenException;
import com.planmate.common.exception.domain.PasswordMismatchException;
import com.planmate.common.exception.domain.TokenExpiredException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.common.mail.MailService;
import com.planmate.domain.auth.dto.ChangePasswordRequest;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PasswordServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "user@example.com";

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SecureRandom secureRandom;
    @Mock
    private MailService mailService;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private PasswordService passwordService;

    @Test
    @DisplayName("verifyPassword 성공 - 현재 비밀번호가 일치하면 예외 없이 종료")
    void verifyPassword_success() {
        User user = mock(User.class);
        given(user.getPassword()).willReturn("encodedPassword");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("rawPassword", "encodedPassword")).willReturn(true);

        assertThatCode(() -> passwordService.verifyPassword(USER_ID, "rawPassword"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("verifyPassword 실패 - userId로 유저를 찾을 수 없음")
    void verifyPassword_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> passwordService.verifyPassword(USER_ID, "rawPassword"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("verifyPassword 실패 - 현재 비밀번호 불일치")
    void verifyPassword_fail_passwordMismatch() {
        User user = mock(User.class);
        given(user.getPassword()).willReturn("encodedPassword");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrongPassword", "encodedPassword")).willReturn(false);

        assertThatThrownBy(() -> passwordService.verifyPassword(USER_ID, "wrongPassword"))
                .isInstanceOf(PasswordMismatchException.class);
    }

    @Test
    @DisplayName("verifyPassword 실패 - OAuth 전용 계정(비밀번호 자체가 없음)은 matches() 호출 없이 즉시 실패")
    void verifyPassword_fail_oauthAccountHasNoPassword() {
        User user = mock(User.class);
        given(user.getPassword()).willReturn(null);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> passwordService.verifyPassword(USER_ID, "anyPassword"))
                .isInstanceOf(PasswordMismatchException.class);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("changePassword 성공 - 현재 비밀번호 검증 후 새 비밀번호로 교체")
    void changePassword_success() {
        User user = mock(User.class);
        given(user.getPassword()).willReturn("encodedCurrentPassword");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("currentPassword", "encodedCurrentPassword")).willReturn(true);
        given(passwordEncoder.encode("newPassword1")).willReturn("encodedNewPassword");

        ChangePasswordRequest request = new ChangePasswordRequest("currentPassword", "newPassword1", "newPassword1");
        passwordService.changePassword(USER_ID, request);

        verify(user).changePassword("encodedNewPassword");
    }

    @Test
    @DisplayName("changePassword 실패 - userId로 유저를 찾을 수 없음")
    void changePassword_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());
        ChangePasswordRequest request = new ChangePasswordRequest("currentPassword", "newPassword1", "newPassword1");

        assertThatThrownBy(() -> passwordService.changePassword(USER_ID, request))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("changePassword 실패 - 현재 비밀번호 불일치")
    void changePassword_fail_currentPasswordMismatch() {
        User user = mock(User.class);
        given(user.getPassword()).willReturn("encodedCurrentPassword");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrongCurrentPassword", "encodedCurrentPassword")).willReturn(false);

        ChangePasswordRequest request = new ChangePasswordRequest("wrongCurrentPassword", "newPassword1", "newPassword1");

        assertThatThrownBy(() -> passwordService.changePassword(USER_ID, request))
                .isInstanceOf(PasswordMismatchException.class);

        verify(user, never()).changePassword(anyString());
    }

    @Test
    @DisplayName("sendTempPassword 성공 - LOCAL 계정에 임시 비밀번호를 반영하고 메일로 평문 발송")
    void sendTempPassword_success() {
        given(jwtTokenProvider.extractVerifiedEmail("verification-token", EmailVerificationPurpose.RESET_PASSWORD))
                .willReturn(EMAIL);
        User user = mock(User.class);
        given(userRepository.findLocalByEmail(EMAIL)).willReturn(Optional.of(user));
        given(secureRandom.nextInt(anyInt())).willReturn(0);
        given(passwordEncoder.encode("AAAAAAAAAA")).willReturn("encodedTempPassword");

        passwordService.sendTempPassword("verification-token");

        verify(user).changePassword("encodedTempPassword");
        verify(mailService).sendTemporaryPasswordMail(EMAIL, "AAAAAAAAAA");
    }

    @Test
    @DisplayName("sendTempPassword 실패 - verificationToken 무효/만료/purpose 불일치")
    void sendTempPassword_fail_invalidToken() {
        given(jwtTokenProvider.extractVerifiedEmail("bad-token", EmailVerificationPurpose.RESET_PASSWORD))
                .willThrow(new InvalidTokenException());

        assertThatThrownBy(() -> passwordService.sendTempPassword("bad-token"))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).findLocalByEmail(anyString());
        verify(mailService, never()).sendTemporaryPasswordMail(anyString(), anyString());
    }

    @Test
    @DisplayName("sendTempPassword 실패 - verificationToken 만료(무효와 별도 예외/에러코드 — 9장 표가 별도 행으로 구분해둔 케이스)")
    void sendTempPassword_fail_expiredToken() {
        given(jwtTokenProvider.extractVerifiedEmail("expired-token", EmailVerificationPurpose.RESET_PASSWORD))
                .willThrow(new TokenExpiredException());

        assertThatThrownBy(() -> passwordService.sendTempPassword("expired-token"))
                .isInstanceOf(TokenExpiredException.class);

        verify(userRepository, never()).findLocalByEmail(anyString());
        verify(mailService, never()).sendTemporaryPasswordMail(anyString(), anyString());
    }

    @Test
    @DisplayName("sendTempPassword 실패 - 검증된 이메일에 대응하는 LOCAL 계정 없음")
    void sendTempPassword_fail_userNotFound() {
        given(jwtTokenProvider.extractVerifiedEmail("verification-token", EmailVerificationPurpose.RESET_PASSWORD))
                .willReturn(EMAIL);
        given(userRepository.findLocalByEmail(EMAIL)).willReturn(Optional.empty());

        assertThatThrownBy(() -> passwordService.sendTempPassword("verification-token"))
                .isInstanceOf(UserNotFoundException.class);

        verify(mailService, never()).sendTemporaryPasswordMail(anyString(), anyString());
    }
}
