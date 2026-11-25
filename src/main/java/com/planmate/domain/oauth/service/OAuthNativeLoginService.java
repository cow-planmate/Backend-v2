package com.planmate.domain.oauth.service;

import com.planmate.common.exception.domain.OAuthEmailConflictException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.auth.dto.LoginResponse;
import com.planmate.domain.auth.repository.RefreshTokenRedisRepository;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.dto.google.GoogleIdTokenLoginResponse;
import com.planmate.domain.oauth.enums.OAuthProvider;
import com.planmate.domain.oauth.service.provider.GoogleIdTokenProfileVerifier;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 앱 네이티브 구글 로그인. 웹 콜백과 같은 판정({@link OAuthLoginService#resolve})을 타고,
 * 결과만 리다이렉트 대신 JSON으로 돌려준다. 기존 사용자는 1회용 코드(/exchange) 없이 바로 토큰을 준다 —
 * 그 코드는 리다이렉트 URL에 토큰이 실리지 않게 하려는 장치라 JSON 응답에는 필요 없다.
 */
@Service
@RequiredArgsConstructor
public class OAuthNativeLoginService {

    private final GoogleIdTokenProfileVerifier googleIdTokenProfileVerifier;
    private final OAuthLoginService oAuthLoginService;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;

    @Transactional
    public GoogleIdTokenLoginResponse loginWithGoogleIdToken(String idToken) {
        OAuthUserProfile profile = googleIdTokenProfileVerifier.verify(idToken);

        OAuthLoginOutcome outcome = oAuthLoginService.resolve(OAuthProvider.GOOGLE, profile);
        if (outcome instanceof OAuthLoginOutcome.ExistingUser existing) {
            return GoogleIdTokenLoginResponse.success(issueTokens(existing.userId()));
        }
        if (outcome instanceof OAuthLoginOutcome.NeedAdditionalInfo need) {
            return GoogleIdTokenLoginResponse.needAdditionalInfo(need.signupId(), need.needEmail());
        }
        throw new OAuthEmailConflictException();   // EmailConflict
    }

    // OAuthExchangeService.exchange 와 같은 발급 절차
    private LoginResponse issueTokens(UUID userId) {
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
                .profileImageUrl(user.getProfileImageUrl())
                .build();
    }
}
