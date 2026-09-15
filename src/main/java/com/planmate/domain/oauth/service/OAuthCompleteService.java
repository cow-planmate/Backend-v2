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
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.internal.outbox.UserOutboxWriter;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OAuthCompleteService {

    private final OAuthSignupRedisRepository oAuthSignupRedisRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;
    private final UserOutboxWriter userOutboxWriter;

    @Transactional
    public LoginResponse complete(OAuthCompleteRequest request) {
        OAuthSignupCache cache = oAuthSignupRedisRepository.find(request.signupId())
                .orElseThrow(OAuthSignupSessionNotFoundException::new);

        String finalEmail = (request.email() != null && !request.email().isBlank())
                ? request.email()
                : cache.email();
        if (finalEmail == null || finalEmail.isBlank()) {
            throw new OAuthEmailRequiredException();
        }

        // 중복가입 방지 — 활성 계정만 확인(A_진입.md 11-1절과 동일한 이유, 레이스 컨디션 방어)
        if (userRepository.existsByProviderAndProviderId(cache.provider(), cache.providerId())) {
            throw new OAuthAlreadyRegisteredException();
        }

        // A 콜백 확인 이후 가입캐시 TTL(최대 15분) 동안 다른 계정이 선점했을 수 있어 재확인(11-2절)
        if (userRepository.existsByEmail(finalEmail)) {
            throw new DuplicateEmailException();
        }
        if (userRepository.existsByNickname(cache.nickname())) {
            throw new DuplicateNicknameException();
        }

        User user = User.builder()
                .provider(cache.provider())
                .providerId(cache.providerId())
                .email(finalEmail)
                .nickname(cache.nickname())
                .password(null)
                .birthdate(request.birthdate())
                .gender(request.gender())
                .role(UserRole.USER)
                .build();

        userRepository.save(user);
        // 소셜 가입도 outbox에 남긴다 — RegisterService.register 와 같은 이유.
        userOutboxWriter.publish(user);
        oAuthSignupRedisRepository.delete(request.signupId());

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
}
