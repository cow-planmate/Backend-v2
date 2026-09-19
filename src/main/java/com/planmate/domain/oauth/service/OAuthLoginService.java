package com.planmate.domain.oauth.service;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthSignupCache;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.enums.OAuthProvider;
import com.planmate.domain.oauth.repository.OAuthLoginCodeRedisRepository;
import com.planmate.domain.oauth.repository.OAuthSignupRedisRepository;
import com.planmate.domain.oauth.service.provider.OAuthProfileFetcher;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.SnsRefreshTokenCipher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OAuthLoginService {

    private final OAuthProperties oAuthProperties;
    private final UserRepository userRepository;
    private final OAuthLoginCodeRedisRepository oAuthLoginCodeRedisRepository;
    private final OAuthSignupRedisRepository oAuthSignupRedisRepository;
    private final OAuthNicknamePolicy nicknamePolicy;
    private final List<OAuthProfileFetcher> profileFetchers;
    private final SnsRefreshTokenCipher snsRefreshTokenCipher;

    private Map<OAuthProvider, OAuthProfileFetcher> fetchersByProvider;

    @PostConstruct
    void init() {
        fetchersByProvider = profileFetchers.stream()
                .collect(Collectors.toMap(OAuthProfileFetcher::supports, Function.identity()));
    }

    /** 1. 로그인 시작 URL 생성 — state는 Controller가 만든 값을 그대로 받아 모든 provider의 쿼리파라미터에 끼워넣음(CSRF 방어용) */
    @Transactional(readOnly = true)
    public String redirectToProvider(OAuthProvider provider, String state) {
        OAuthProperties.Provider config = oAuthProperties.getProvider(provider);

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(config.getAuthorizeUri())
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", config.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state);

        if (provider == OAuthProvider.GOOGLE) {
            builder.queryParam("access_type", "offline")
                    .queryParam("prompt", "consent");
        }

        if (config.getScope() != null) {
            builder.queryParam("scope", config.getScope());
        }

        return builder.toUriString();
    }

    /**
     * 2. callback 처리 — state는 Controller가 쿠키 대조로 CSRF 검증을 마친 값이지만,
     * NAVER 토큰 교환 API 자체가 이 값을 요청 바디에 그대로 요구하므로 폐기하지 않고 그대로 전달받는다.
     */
    @Transactional
    public String callback(OAuthProvider provider, String code, String state) {

        OAuthUserProfile profile = fetchersByProvider.get(provider).fetch(code, state);

        String email = profile.getEmail();
        boolean needEmail = (email == null || email.isBlank());
        String providerId = profile.getProviderId();

        // 활성 계정만 조회 — 탈퇴 계정은 로컬과 동일하게 "없음" 취급되어 아래 신규가입 분기로 흐른다
        // (재가입 정책 변경: 자동 복구 대신 새 계정 생성, uk_users_provider_provider_id가
        //  V14부터 WHERE deleted_at IS NULL 조건을 가져 탈퇴 후 동일 provider+providerId 재사용 가능)
        Optional<User> existingUser = userRepository.findByProviderAndProviderId(provider.toUserProvider(), providerId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            // GOOGLE만 매 로그인마다 refresh_token을 다시 내려준다(prompt=consent) — 받자마자 즉시
            // 암호화한다. null(KAKAO/NAVER, 또는 GOOGLE인데 이례적으로 안 내려온 경우)이면 기존 값을
            // 건드리지 않는다 — 유효했던 이전 값을 이유 없이 지우지 않기 위해서다.
            // (04_기능/OAuth/C_SNS_연동해제.md 2-1절)
            if (profile.getRefreshToken() != null) {
                user.changeSnsRefreshToken(snsRefreshTokenCipher.encrypt(profile.getRefreshToken()));
            }
            String loginCode = oAuthLoginCodeRedisRepository.issue(user.getUserId());
            return buildLoginCodeRedirect(loginCode);
        }

        if (email != null) {
            Optional<User> conflicting = userRepository.findByEmailIgnoreCase(email);
            if (conflicting.isPresent() && conflicting.get().getProvider() != provider.toUserProvider()) {
                return buildFailRedirect();
            }
        }

        String sanitized = nicknamePolicy.sanitize(profile.getNickname());
        String finalNickname = nicknamePolicy.resolveUnique(sanitized);

        // 암호화는 여기(콜백, 받는 즉시)에서 끝낸다 — B(가입완료)로 미루면 A~B 사이(최대 TTL
        // 15분) Redis에 원문 refresh_token이 그대로 노출된다(04_기능/OAuth/C_SNS_연동해제.md 2-2절).
        String encryptedRefreshToken = profile.getRefreshToken() != null
                ? snsRefreshTokenCipher.encrypt(profile.getRefreshToken())
                : null;

        OAuthSignupCache cache = new OAuthSignupCache(
                provider.toUserProvider(),
                providerId,
                email,
                finalNickname,
                encryptedRefreshToken
        );
        String signupId = oAuthSignupRedisRepository.save(cache);

        return buildAdditionalInfoRedirect(signupId, needEmail);
    }

    /** 기존 SNS 유저 로그인 → loginCode redirect */
    private String buildLoginCodeRedirect(String code) {
        return UriComponentsBuilder
                .fromUriString(oAuthProperties.getFrontendRedirectUri())
                .queryParam("status", "SUCCESS")
                .queryParam("code", code)
                .build(true)
                .toUriString();
    }

    /** 신규 SNS 유저 → 추가정보 입력 redirect */
    private String buildAdditionalInfoRedirect(String signupId, boolean needEmail) {
        return UriComponentsBuilder
                .fromUriString(oAuthProperties.getFrontendRedirectUri())
                .queryParam("status", "NEED_ADDITIONAL_INFO")
                .queryParam("signupId", signupId)
                .queryParam("needEmail", needEmail)
                .build(true)
                .toUriString();
    }

    private String buildFailRedirect() {
        return UriComponentsBuilder
                .fromUriString(oAuthProperties.getFrontendRedirectUri())
                .queryParam("status", "FAIL")
                .queryParam("reason", "EMAIL_CONFLICT")
                .build(true)
                .toUriString();
    }

}
