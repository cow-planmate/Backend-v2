package com.planmate.domain.oauth.service;

import com.planmate.common.exception.domain.InvalidTokenException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.auth.dto.LoginResponse;
import com.planmate.domain.auth.repository.RefreshTokenRedisRepository;
import com.planmate.domain.oauth.repository.OAuthLoginCodeRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OAuthExchangeService {

    private final OAuthLoginCodeRedisRepository oAuthLoginCodeRedisRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;

    @Transactional(readOnly = true)
    public LoginResponse exchange(String code) {
        UUID userId = oAuthLoginCodeRedisRepository.consume(code)
                .orElseThrow(InvalidTokenException::new);

        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        String accessToken = jwtTokenProvider.createAccessToken(
                user.getUserId(), user.getRole().name(), user.isSuspended());
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
