package com.planmate.domain.oauth.service;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthSignupCache;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.dto.google.GoogleTokenResponse;
import com.planmate.domain.oauth.dto.google.GoogleUserResponse;
import com.planmate.domain.oauth.dto.kakao.KakaoTokenResponse;
import com.planmate.domain.oauth.dto.kakao.KakaoUserResponse;
import com.planmate.domain.oauth.dto.naver.NaverTokenResponse;
import com.planmate.domain.oauth.dto.naver.NaverUserResponse;
import com.planmate.domain.oauth.enums.OAuthProvider;
import com.planmate.domain.oauth.repository.OAuthLoginCodeRedisRepository;
import com.planmate.domain.oauth.repository.OAuthSignupRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OAuthLoginService {

    private final OAuthProperties oAuthProperties;
    private final UserRepository userRepository;
    private final OAuthLoginCodeRedisRepository oAuthLoginCodeRedisRepository;
    private final OAuthSignupRedisRepository oAuthSignupRedisRepository;
    private final RestTemplate restTemplate;

    /** 1. 로그인 시작 URL 생성 — state는 Controller가 만든 값을 그대로 받아 쿼리파라미터에 끼워넣기만 함(NAVER 외엔 무시) */
    @Transactional(readOnly = true)
    public String redirectToProvider(OAuthProvider provider, String state) {
        OAuthProperties.Provider config = oAuthProperties.getProvider(provider);

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(config.getAuthorizeUri())
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", config.getRedirectUri())
                .queryParam("response_type", "code");

        switch (provider) {
            case GOOGLE -> builder.queryParam("access_type", "offline")
                    .queryParam("prompt", "consent");
            case NAVER -> builder.queryParam("state", state);
            default -> {
            }
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

        OAuthUserProfile profile = switch (provider) {
            case KAKAO -> fetchKakaoProfile(code);
            case GOOGLE -> fetchGoogleProfile(code);
            case NAVER -> fetchNaverProfile(code, state);
        };

        String providerName = provider.name();
        String email = profile.getEmail();
        boolean needEmail = (email == null || email.isBlank());
        String providerId = profile.getProviderId();

        // 탈퇴 계정 포함 조회(OAuth 재가입 정책, 데이터베이스_설계_v2.md 6.8절)
        Optional<User> existingUser =
                userRepository.findByProviderAndProviderIdIncludingDeleted(providerName, providerId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();

            if (user.isDeleted()) {
                // 닉네임 재확인(11-3절)을 restore()보다 먼저 계산한다 — restore()를 먼저 호출하면
                // resolveUniqueNickname 내부의 existsByNickname 쿼리 실행 직전 Hibernate가 restore()로 생긴
                // 변경사항(deletedAt=null)만 먼저 auto-flush해버려서, 닉네임이 아직 안 바뀐 채로 활성화된
                // 순간적인 DB 상태가 생기고 그 시점에 uk_users_nickname이 위반된다(실제 DB 통합 테스트로 재현 확인).
                // 닉네임을 먼저 확정한 뒤 restore()+changeNickname()을 연달아 호출해 하나의 UPDATE로 flush되게 한다.
                String reconfirmedNickname = resolveUniqueNickname(user.getNickname());
                user.restore();
                user.changeNickname(reconfirmedNickname);
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

        String sanitized = sanitizeNickname(profile.getNickname());
        String finalNickname = resolveUniqueNickname(sanitized);

        OAuthSignupCache cache = new OAuthSignupCache(
                provider.toUserProvider(),
                providerId,
                email,
                finalNickname
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

    private String sanitizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "user";
        }

        String cleaned = nickname.trim();
        cleaned = cleaned.replaceAll("\\s+", "");
        cleaned = cleaned.replaceAll("[\\p{So}\\p{Cn}]", "");
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9가-힣]", "");

        if (cleaned.isBlank()) {
            cleaned = "user";
        }

        if (cleaned.length() > 20) {
            cleaned = cleaned.substring(0, 20);
        }

        return cleaned;
    }

    private String resolveUniqueNickname(String baseNickname) {
        if (!userRepository.existsByNickname(baseNickname)) {
            return baseNickname;
        }

        int index = 1;
        String candidate;
        do {
            candidate = baseNickname + index;
            index++;
        } while (userRepository.existsByNickname(candidate));

        return candidate;
    }

    /* ================= PROVIDER 구현부 ================= */

    private OAuthUserProfile fetchKakaoProfile(String code) {
        OAuthProperties.Provider config = oAuthProperties.getProvider(OAuthProvider.KAKAO);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", config.getClientId());
        body.add("client_secret", config.getClientSecret());
        body.add("redirect_uri", config.getRedirectUri());
        body.add("code", code);

        KakaoTokenResponse token = restTemplate.postForObject(
                config.getTokenUri(),
                new HttpEntity<>(body, headers),
                KakaoTokenResponse.class
        );

        HttpHeaders infoHeaders = new HttpHeaders();
        infoHeaders.setBearerAuth(token.getAccessToken());

        ResponseEntity<KakaoUserResponse> response = restTemplate.exchange(
                config.getUserInfoUri(),
                HttpMethod.GET,
                new HttpEntity<>(infoHeaders),
                KakaoUserResponse.class
        );

        KakaoUserResponse d = response.getBody();

        return new OAuthUserProfile(
                String.valueOf(d.getId()),
                d.getKakaoAccount().getEmail(),
                d.getKakaoAccount().getProfile().getNickname()
        );
    }

    private OAuthUserProfile fetchGoogleProfile(String code) {
        OAuthProperties.Provider config = oAuthProperties.getProvider(OAuthProvider.GOOGLE);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", config.getClientId());
        body.add("client_secret", config.getClientSecret());
        body.add("redirect_uri", config.getRedirectUri());
        body.add("code", code);

        GoogleTokenResponse token = restTemplate.postForObject(
                config.getTokenUri(),
                new HttpEntity<>(body, headers),
                GoogleTokenResponse.class
        );

        HttpHeaders infoHeaders = new HttpHeaders();
        infoHeaders.setBearerAuth(token.getAccessToken());

        ResponseEntity<GoogleUserResponse> response = restTemplate.exchange(
                config.getUserInfoUri(),
                HttpMethod.GET,
                new HttpEntity<>(infoHeaders),
                GoogleUserResponse.class
        );

        GoogleUserResponse d = response.getBody();

        return new OAuthUserProfile(
                d.getSub(),
                d.getEmail(),
                d.getName()
        );
    }

    private OAuthUserProfile fetchNaverProfile(String code, String state) {
        OAuthProperties.Provider config = oAuthProperties.getProvider(OAuthProvider.NAVER);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", config.getClientId());
        body.add("client_secret", config.getClientSecret());
        body.add("redirect_uri", config.getRedirectUri());
        body.add("code", code);
        body.add("state", state);

        NaverTokenResponse token = restTemplate.postForObject(
                config.getTokenUri(),
                new HttpEntity<>(body, headers),
                NaverTokenResponse.class
        );

        HttpHeaders infoHeaders = new HttpHeaders();
        infoHeaders.setBearerAuth(token.getAccessToken());

        ResponseEntity<NaverUserResponse> response = restTemplate.exchange(
                config.getUserInfoUri(),
                HttpMethod.GET,
                new HttpEntity<>(infoHeaders),
                NaverUserResponse.class
        );

        NaverUserResponse.Result r = response.getBody().getResponse();

        return new OAuthUserProfile(
                r.getId(),
                r.getEmail(),
                r.getNickname()
        );
    }
}
