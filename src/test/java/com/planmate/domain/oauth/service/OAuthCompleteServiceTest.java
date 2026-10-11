package com.planmate.domain.oauth.service;

import com.planmate.common.exception.domain.DuplicateEmailException;
import com.planmate.common.exception.domain.DuplicateNicknameException;
import com.planmate.common.exception.domain.OAuthAlreadyRegisteredException;
import com.planmate.common.exception.domain.OAuthEmailRequiredException;
import com.planmate.common.exception.domain.OAuthSignupSessionNotFoundException;
import com.planmate.domain.auth.dto.LoginResponse;
import com.planmate.domain.auth.repository.RefreshTokenRedisRepository;
import com.planmate.domain.oauth.dto.OAuthCompleteRequest;
import com.planmate.domain.oauth.dto.OAuthSignupCache;
import com.planmate.domain.oauth.repository.OAuthSignupRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
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

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OAuthCompleteServiceTest {

    private static final String SIGNUP_ID = "signup-id-123";

    @Mock
    private OAuthSignupRedisRepository oAuthSignupRedisRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private RefreshTokenRedisRepository refreshTokenRedisRepository;

    @Mock
    private UserOutboxWriter userOutboxWriter;

    @InjectMocks
    private OAuthCompleteService oAuthCompleteService;

    private OAuthSignupCache cacheWithEmail() {
        return new OAuthSignupCache(UserProvider.KAKAO, "111", "cached@example.com", "홍길동", null);
    }

    private OAuthCompleteRequest requestWithoutEmail() {
        return new OAuthCompleteRequest(SIGNUP_ID, null, LocalDate.of(1998, 1, 1), Gender.MALE);
    }

    @Test
    @DisplayName("가입 완료 성공 - 가입캐시 이메일 사용, User 생성 및 토큰 발급, 캐시 삭제")
    void completeRegistration_success() {
        given(oAuthSignupRedisRepository.find(SIGNUP_ID)).willReturn(Optional.of(cacheWithEmail()));
        given(userRepository.existsByProviderAndProviderId(UserProvider.KAKAO, "111")).willReturn(false);
        given(userRepository.existsByEmail("cached@example.com")).willReturn(false);
        given(userRepository.existsByNickname("홍길동")).willReturn(false);
        given(jwtTokenProvider.createAccessToken(any(), org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.anyBoolean())).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh-token");
        given(jwtTokenProvider.getRefreshTokenExpiry()).willReturn(Duration.ofDays(7));

        LoginResponse response = oAuthCompleteService.complete(requestWithoutEmail());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        assertThat(savedUser.getProvider()).isEqualTo(UserProvider.KAKAO);
        assertThat(savedUser.getProviderId()).isEqualTo("111");
        assertThat(savedUser.getEmail()).isEqualTo("cached@example.com");
        assertThat(savedUser.getNickname()).isEqualTo("홍길동");
        assertThat(savedUser.getPassword()).isNull();
        assertThat(savedUser.getBirthdate()).isEqualTo(LocalDate.of(1998, 1, 1));
        assertThat(savedUser.getGender()).isEqualTo(Gender.MALE);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");

        verify(oAuthSignupRedisRepository).delete(SIGNUP_ID);
    }

    @Test
    @DisplayName("가입 완료 성공 - 가입캐시의 (이미 암호화된) SNS refresh_token을 복호화/재암호화 없이 그대로 User에 옮겨 담는다 (C_SNS_연동해제.md 2-2절)")
    void completeRegistration_copiesEncryptedRefreshTokenAsIs() {
        OAuthSignupCache cacheWithRefreshToken =
                new OAuthSignupCache(UserProvider.GOOGLE, "111", "cached@example.com", "홍길동", "already-encrypted-value");
        given(oAuthSignupRedisRepository.find(SIGNUP_ID)).willReturn(Optional.of(cacheWithRefreshToken));
        given(userRepository.existsByProviderAndProviderId(UserProvider.GOOGLE, "111")).willReturn(false);
        given(userRepository.existsByEmail("cached@example.com")).willReturn(false);
        given(userRepository.existsByNickname("홍길동")).willReturn(false);
        given(jwtTokenProvider.createAccessToken(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh-token");
        given(jwtTokenProvider.getRefreshTokenExpiry()).willReturn(Duration.ofDays(7));

        oAuthCompleteService.complete(requestWithoutEmail());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getSnsRefreshToken()).isEqualTo("already-encrypted-value");
    }

    @Test
    @DisplayName("가입 완료 성공 - 요청의 email이 가입캐시 email보다 우선")
    void completeRegistration_requestEmailOverridesCache() {
        given(oAuthSignupRedisRepository.find(SIGNUP_ID)).willReturn(Optional.of(cacheWithEmail()));
        given(userRepository.existsByProviderAndProviderId(UserProvider.KAKAO, "111")).willReturn(false);
        given(userRepository.existsByEmail("user-provided@example.com")).willReturn(false);
        given(userRepository.existsByNickname("홍길동")).willReturn(false);
        given(jwtTokenProvider.createAccessToken(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh-token");
        given(jwtTokenProvider.getRefreshTokenExpiry()).willReturn(Duration.ofDays(7));

        OAuthCompleteRequest request = new OAuthCompleteRequest(SIGNUP_ID, "user-provided@example.com", null, null);
        oAuthCompleteService.complete(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("user-provided@example.com");
    }

    @Test
    @DisplayName("가입 완료 실패 - signupId로 가입 세션을 찾을 수 없음(만료/위조)")
    void completeRegistration_fail_signupSessionNotFound() {
        given(oAuthSignupRedisRepository.find(SIGNUP_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> oAuthCompleteService.complete(requestWithoutEmail()))
                .isInstanceOf(OAuthSignupSessionNotFoundException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("가입 완료 실패 - SNS도 이메일을 안 줬고 사용자도 입력 안 함")
    void completeRegistration_fail_emailRequired() {
        OAuthSignupCache cacheWithoutEmail = new OAuthSignupCache(UserProvider.KAKAO, "111", null, "홍길동", null);
        given(oAuthSignupRedisRepository.find(SIGNUP_ID)).willReturn(Optional.of(cacheWithoutEmail));

        assertThatThrownBy(() -> oAuthCompleteService.complete(requestWithoutEmail()))
                .isInstanceOf(OAuthEmailRequiredException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("가입 완료 실패 - 이미 가입된 계정(동일 provider+providerId, 레이스 컨디션)")
    void completeRegistration_fail_alreadyRegistered() {
        given(oAuthSignupRedisRepository.find(SIGNUP_ID)).willReturn(Optional.of(cacheWithEmail()));
        given(userRepository.existsByProviderAndProviderId(UserProvider.KAKAO, "111")).willReturn(true);

        assertThatThrownBy(() -> oAuthCompleteService.complete(requestWithoutEmail()))
                .isInstanceOf(OAuthAlreadyRegisteredException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("가입 완료 실패 - 최종 이메일이 다른 계정에 이미 등록됨(A 콜백 이후 레이스)")
    void completeRegistration_fail_duplicateEmail() {
        given(oAuthSignupRedisRepository.find(SIGNUP_ID)).willReturn(Optional.of(cacheWithEmail()));
        given(userRepository.existsByProviderAndProviderId(UserProvider.KAKAO, "111")).willReturn(false);
        given(userRepository.existsByEmail("cached@example.com")).willReturn(true);

        assertThatThrownBy(() -> oAuthCompleteService.complete(requestWithoutEmail()))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("가입 완료 실패 - 닉네임이 다른 계정에 이미 등록됨(A의 resolveUniqueNickname 이후 레이스)")
    void completeRegistration_fail_duplicateNickname() {
        given(oAuthSignupRedisRepository.find(SIGNUP_ID)).willReturn(Optional.of(cacheWithEmail()));
        given(userRepository.existsByProviderAndProviderId(UserProvider.KAKAO, "111")).willReturn(false);
        given(userRepository.existsByEmail("cached@example.com")).willReturn(false);
        given(userRepository.existsByNickname("홍길동")).willReturn(true);

        assertThatThrownBy(() -> oAuthCompleteService.complete(requestWithoutEmail()))
                .isInstanceOf(DuplicateNicknameException.class);

        verify(userRepository, never()).save(any());
    }
}
