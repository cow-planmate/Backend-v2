package com.planmate.domain.auth.service;

import com.planmate.common.exception.domain.DuplicateEmailException;
import com.planmate.common.exception.domain.InvalidVerificationCodeException;
import com.planmate.common.exception.domain.VerificationCodeNotFoundException;
import com.planmate.common.mail.MailService;
import com.planmate.domain.auth.dto.EmailVerificationConfirmRequest;
import com.planmate.domain.auth.dto.EmailVerificationConfirmResponse;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.auth.repository.EmailVerificationRedisRepository;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private UserRepository userRepository;
    @Mock
    private SecureRandom secureRandom;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private MailService mailService;
    @Mock
    private EmailVerificationRedisRepository emailVerificationRedisRepository;

    @InjectMocks
    private EmailVerificationService emailVerificationService;

    @Test
    @DisplayName("발송 성공 - SIGN_UP, 미가입 상태면 코드 생성/Redis 저장/메일 발송")
    void sendVerificationCode_signUp_success() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(secureRandom.nextInt(900000)).willReturn(23456);

        emailVerificationService.sendVerificationCode(EMAIL, EmailVerificationPurpose.SIGN_UP);

        verify(emailVerificationRedisRepository).saveCode(EMAIL, EmailVerificationPurpose.SIGN_UP, 123456, Duration.ofMinutes(5));
        verify(mailService).sendVerificationCodeMail(eq(EMAIL), anyString(), anyString(), anyString(), eq("123456"));
    }

    @Test
    @DisplayName("발송 성공 - RESET_PASSWORD, 가입된 이메일이면 코드 생성/Redis 저장/메일 발송")
    void sendVerificationCode_resetPassword_success() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);
        given(secureRandom.nextInt(900000)).willReturn(23456);

        emailVerificationService.sendVerificationCode(EMAIL, EmailVerificationPurpose.RESET_PASSWORD);

        verify(emailVerificationRedisRepository).saveCode(EMAIL, EmailVerificationPurpose.RESET_PASSWORD, 123456, Duration.ofMinutes(5));
        verify(mailService).sendVerificationCodeMail(eq(EMAIL), anyString(), anyString(), anyString(), eq("123456"));
    }

    @Test
    @DisplayName("발송 실패 - SIGN_UP인데 이미 가입된 이메일이면 계정 존재를 그대로 노출(DuplicateEmailException)")
    void sendVerificationCode_signUp_duplicateEmail_throws() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        assertThatThrownBy(() -> emailVerificationService.sendVerificationCode(EMAIL, EmailVerificationPurpose.SIGN_UP))
                .isInstanceOf(DuplicateEmailException.class);

        verify(emailVerificationRedisRepository, never()).saveCode(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        verify(mailService, never()).sendVerificationCodeMail(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("발송 - RESET_PASSWORD인데 미가입 이메일이면 계정 존재를 노출하지 않고 조용히 성공 처리(예외 없음, 코드/메일 생성 생략)")
    void sendVerificationCode_resetPassword_emailNotFound_silentlySucceeds() {
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);

        assertThatCode(() -> emailVerificationService.sendVerificationCode(EMAIL, EmailVerificationPurpose.RESET_PASSWORD))
                .doesNotThrowAnyException();

        verify(emailVerificationRedisRepository, never()).saveCode(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        verify(mailService, never()).sendVerificationCodeMail(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("확인 성공 - 인증코드가 일치하면 Redis 삭제 후 인증 토큰을 반환")
    void confirmVerificationCode_success() {
        EmailVerificationConfirmRequest request = new EmailVerificationConfirmRequest(EMAIL, EmailVerificationPurpose.SIGN_UP, 123456);
        given(emailVerificationRedisRepository.findCode(EMAIL, EmailVerificationPurpose.SIGN_UP)).willReturn(Optional.of(123456));
        given(jwtTokenProvider.createEmailVerificationToken(EMAIL, EmailVerificationPurpose.SIGN_UP)).willReturn("verification-token");

        EmailVerificationConfirmResponse response = emailVerificationService.confirmVerificationCode(request);

        assertThat(response.verificationToken()).isEqualTo("verification-token");
        verify(emailVerificationRedisRepository).delete(EMAIL, EmailVerificationPurpose.SIGN_UP);
    }

    @Test
    @DisplayName("확인 실패 - Redis에 인증 요청이 없음(만료 또는 미발송)")
    void confirmVerificationCode_fail_codeNotFound() {
        EmailVerificationConfirmRequest request = new EmailVerificationConfirmRequest(EMAIL, EmailVerificationPurpose.SIGN_UP, 123456);
        given(emailVerificationRedisRepository.findCode(EMAIL, EmailVerificationPurpose.SIGN_UP)).willReturn(Optional.empty());

        assertThatThrownBy(() -> emailVerificationService.confirmVerificationCode(request))
                .isInstanceOf(VerificationCodeNotFoundException.class);

        verify(emailVerificationRedisRepository, never()).delete(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("확인 실패 - 인증코드 불일치")
    void confirmVerificationCode_fail_codeMismatch() {
        EmailVerificationConfirmRequest request = new EmailVerificationConfirmRequest(EMAIL, EmailVerificationPurpose.SIGN_UP, 999999);
        given(emailVerificationRedisRepository.findCode(EMAIL, EmailVerificationPurpose.SIGN_UP)).willReturn(Optional.of(123456));

        assertThatThrownBy(() -> emailVerificationService.confirmVerificationCode(request))
                .isInstanceOf(InvalidVerificationCodeException.class);

        verify(emailVerificationRedisRepository, never()).delete(anyString(), org.mockito.ArgumentMatchers.any());
    }
}
