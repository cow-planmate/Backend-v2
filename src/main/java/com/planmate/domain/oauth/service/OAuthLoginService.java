package com.planmate.domain.oauth.service;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthSignupCache;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.enums.OAuthProvider;
import com.planmate.domain.oauth.repository.OAuthLoginCodeRedisRepository;
import com.planmate.domain.oauth.repository.OAuthSignupRedisRepository;
import com.planmate.domain.oauth.service.provider.OAuthProfileFetcher;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.internal.outbox.UserOutboxWriter;
import com.planmate.domain.user.repository.UserRepository;
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
    private final UserOutboxWriter userOutboxWriter;
    private final OAuthNicknamePolicy nicknamePolicy;
    private final List<OAuthProfileFetcher> profileFetchers;

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
                String reconfirmedNickname = nicknamePolicy.resolveUnique(user.getNickname());
                user.restore();
                user.changeNickname(reconfirmedNickname);
                // 재활성화는 지금까지 아무 알림도 내보내지 않았다 — 탈퇴 상태로 복제돼 있던
                // 사용자가 살아나고 닉네임까지 바뀌는데 커뮤니티는 모른다. 페이로드가 전체
                // 스냅샷이라 restore + changeNickname 을 한 행으로 덮으면 된다.
                userOutboxWriter.publish(user);
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

}
