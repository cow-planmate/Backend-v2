package com.planmate.domain.oauth.service;

import com.planmate.common.exception.domain.InvalidTokenException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.auth.dto.LoginResponse;
import com.planmate.domain.auth.repository.RefreshTokenRedisRepository;
import com.planmate.domain.oauth.repository.OAuthLoginCodeRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OAuthExchangeServiceTest {

    private static final String CODE = "one-time-login-code";
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private OAuthLoginCodeRedisRepository oAuthLoginCodeRedisRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private RefreshTokenRedisRepository refreshTokenRedisRepository;

    @InjectMocks
    private OAuthExchangeService oAuthExchangeService;

    private User buildUser() {
        return User.builder()
                .userId(USER_ID)
                .provider(UserProvider.KAKAO)
                .providerId("111")
                .email("user@example.com")
                .nickname("홍길동")
                .role(UserRole.USER)
                .build();
    }

    @Test
    @DisplayName("교환 성공 - 유효한 로그인코드 → Access/Refresh 토큰 발급")
    void exchange_success() {
        User user = buildUser();
        given(oAuthLoginCodeRedisRepository.consume(CODE)).willReturn(Optional.of(USER_ID));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(jwtTokenProvider.createAccessToken(USER_ID, "USER")).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken(USER_ID)).willReturn("refresh-token");
        given(jwtTokenProvider.getRefreshTokenExpiry()).willReturn(Duration.ofDays(7));

        LoginResponse response = oAuthExchangeService.exchange(CODE);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.nickname()).isEqualTo("홍길동");
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.role()).isEqualTo("USER");
        verify(refreshTokenRedisRepository).save("refresh-token", USER_ID, Duration.ofDays(7));
    }

    @Test
    @DisplayName("교환 실패 - 로그인코드가 무효/만료/이미 소비됨")
    void exchange_fail_invalidCode() {
        given(oAuthLoginCodeRedisRepository.consume(CODE)).willReturn(Optional.empty());

        assertThatThrownBy(() -> oAuthExchangeService.exchange(CODE))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("교환 실패 - 코드는 유효하지만 그 사이 유저가 존재하지 않게 됨")
    void exchange_fail_userNotFound() {
        given(oAuthLoginCodeRedisRepository.consume(CODE)).willReturn(Optional.of(USER_ID));
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> oAuthExchangeService.exchange(CODE))
                .isInstanceOf(UserNotFoundException.class);

        verify(jwtTokenProvider, never()).createAccessToken(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
