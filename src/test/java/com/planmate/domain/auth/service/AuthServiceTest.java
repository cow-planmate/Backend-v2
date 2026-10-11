package com.planmate.domain.auth.service;

import com.planmate.common.exception.domain.InvalidCredentialsException;
import com.planmate.common.exception.domain.InvalidTokenException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.auth.dto.LoginResponse;
import com.planmate.domain.auth.dto.RefreshTokenResponse;
import com.planmate.domain.auth.repository.RefreshTokenRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "raw-password";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private RefreshTokenRedisRepository refreshTokenRedisRepository;

    @InjectMocks
    private AuthService authService;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder()
                .userId(userId)
                .provider(UserProvider.LOCAL)
                .email(EMAIL)
                .password("encoded-hash")
                .nickname("nick")
                .role(UserRole.USER)
                .build();
    }

    @Test
    @DisplayName("로그인 성공 - Access/Refresh 토큰 발급 및 Redis 저장")
    void login_success() {
        given(userRepository.findLocalByEmail(EMAIL)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, user.getPassword())).willReturn(true);
        given(jwtTokenProvider.createAccessToken(userId, "USER", false)).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken(userId)).willReturn("refresh-token");
        given(jwtTokenProvider.getRefreshTokenExpiry()).willReturn(Duration.ofDays(7));

        LoginResponse response = authService.login(EMAIL, PASSWORD);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.nickname()).isEqualTo("nick");
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.role()).isEqualTo("USER");
        verify(refreshTokenRedisRepository).save(eq("refresh-token"), eq(userId), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("로그인 실패 - 계정 없음(OAuth 전용 계정 포함, findLocalByEmail이 이미 필터링) - 더미 해시로도 matches는 실행")
    void login_fail_accountNotFound_stillRunsMatchesForTimingAttackMitigation() {
        given(userRepository.findLocalByEmail(EMAIL)).willReturn(Optional.empty());
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

        assertThatThrownBy(() -> authService.login(EMAIL, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder, times(1)).matches(eq(PASSWORD), anyString());
    }

    @Test
    @DisplayName("로그인 실패 - 비밀번호 불일치")
    void login_fail_passwordMismatch() {
        given(userRepository.findLocalByEmail(EMAIL)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, user.getPassword())).willReturn(false);

        assertThatThrownBy(() -> authService.login(EMAIL, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("로그인 성공 - 정지된 계정도 로그인은 되고, 액세스 토큰에 정지 여부(suspended=true)를 싣는다")
    void login_success_accountSuspended_issuesSuspendedAccessToken() {
        user.suspend(LocalDateTime.now().plusDays(3), "약관 위반");
        given(userRepository.findLocalByEmail(EMAIL)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, user.getPassword())).willReturn(true);
        given(jwtTokenProvider.createAccessToken(userId, "USER", true)).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken(userId)).willReturn("refresh-token");
        given(jwtTokenProvider.getRefreshTokenExpiry()).willReturn(Duration.ofDays(7));

        LoginResponse response = authService.login(EMAIL, PASSWORD);

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(jwtTokenProvider).createAccessToken(userId, "USER", true);
        verify(refreshTokenRedisRepository).save(eq("refresh-token"), eq(userId), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("로그인 성공 - 비밀번호가 틀리면 정지 여부와 무관하게 InvalidCredentialsException(정지 사실이 새지 않음)")
    void login_fail_passwordMismatch_evenIfSuspended() {
        user.suspend(LocalDateTime.now().plusDays(3), "약관 위반");
        given(userRepository.findLocalByEmail(EMAIL)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, user.getPassword())).willReturn(false);

        assertThatThrownBy(() -> authService.login(EMAIL, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("로그아웃 - refreshToken이 있으면 Redis에서 삭제")
    void logout_withToken_deletesFromRedis() {
        authService.logout("refresh-token");

        verify(refreshTokenRedisRepository).delete("refresh-token");
    }

    @Test
    @DisplayName("로그아웃 - refreshToken이 비어있으면 아무 것도 안 하고 성공 처리(관대한 처리)")
    void logout_blankToken_doesNothing() {
        authService.logout("");
        authService.logout(null);

        verify(refreshTokenRedisRepository, never()).delete(anyString());
    }

    @Test
    @DisplayName("재발급 성공 - 새 Access Token만 발급, Refresh Token은 그대로 둠")
    void reissue_success() {
        given(refreshTokenRedisRepository.findUserId("refresh-token")).willReturn(Optional.of(userId));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(jwtTokenProvider.createAccessToken(userId, "USER", false)).willReturn("new-access-token");

        RefreshTokenResponse response = authService.reissue("refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access-token");
    }

    @Test
    @DisplayName("재발급 실패 - Redis에 Refresh Token 없음(만료/미존재/이미 로그아웃)")
    void reissue_fail_tokenNotInRedis() {
        given(refreshTokenRedisRepository.findUserId("refresh-token")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reissue("refresh-token"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("재발급 실패 - Redis엔 있으나 유저가 존재하지 않음(탈퇴 등 극단 케이스)")
    void reissue_fail_userNotFound() {
        given(refreshTokenRedisRepository.findUserId("refresh-token")).willReturn(Optional.of(userId));
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reissue("refresh-token"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("재발급 성공 - 정지된 계정도 재발급은 되고, 새 액세스 토큰에 정지 여부(suspended=true)를 싣는다")
    void reissue_success_accountSuspended_issuesSuspendedAccessToken() {
        user.suspend(LocalDateTime.now().plusDays(3), "약관 위반");
        given(refreshTokenRedisRepository.findUserId("refresh-token")).willReturn(Optional.of(userId));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(jwtTokenProvider.createAccessToken(userId, "USER", true)).willReturn("new-access-token");

        RefreshTokenResponse response = authService.reissue("refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        verify(jwtTokenProvider).createAccessToken(userId, "USER", true);
    }
}
