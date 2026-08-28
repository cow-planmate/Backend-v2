package com.planmate.domain.auth.service;

import com.planmate.common.exception.domain.AccountSuspendedException;
import com.planmate.common.exception.domain.InvalidCredentialsException;
import com.planmate.common.exception.domain.InvalidTokenException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.auth.dto.LoginResponse;
import com.planmate.domain.auth.dto.RefreshTokenResponse;
import com.planmate.domain.auth.repository.RefreshTokenRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    // 계정 없음/OAuth 전용 계정일 때도 matches()를 실행해 응답 시간을 동일하게 맞추기 위한 더미 해시
    // (실제 사용자 비밀번호가 아니므로 로그인에 성공할 수 없음, A_세션_토큰.md 9장 타이밍 공격 대응)
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N3wCVb2VtaMIDKE8jPXp.urwMJiu7EPg77ed1PG3/SdIbIjVAXCtm";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;

    @Transactional(readOnly = true)
    public LoginResponse login(String email, String password) {
        User user = userRepository.findLocalByEmail(email).orElse(null);
        String hashToCheck = (user != null) ? user.getPassword() : DUMMY_PASSWORD_HASH;
        boolean matches = passwordEncoder.matches(password, hashToCheck);

        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }
        // 비밀번호 검증 뒤에 둔다 — 틀린 비밀번호로도 "정지된 계정"이 드러나면 계정 상태가
        // 크리덴셜 없이도 열람 가능한 정보가 된다(오라클 공격 방지).
        if (user.isSuspended()) {
            throw new AccountSuspendedException(user.getSuspendedUntil(), user.getSuspendReason());
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserId());
        refreshTokenRedisRepository.save(refreshToken, user.getUserId(), jwtTokenProvider.getRefreshTokenExpiry());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getUserId())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenRedisRepository.delete(refreshToken);
        }
    }

    @Transactional(readOnly = true)
    public RefreshTokenResponse reissue(String refreshToken) {
        UUID userId = refreshTokenRedisRepository.findUserId(refreshToken)
                .orElseThrow(InvalidTokenException::new);
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);
        if (user.isSuspended()) {
            throw new AccountSuspendedException(user.getSuspendedUntil(), user.getSuspendReason());
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());
        return new RefreshTokenResponse(accessToken);
    }
}
