package com.planmate.domain.oauth.service;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthSignupCache;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.enums.OAuthProvider;
import com.planmate.domain.oauth.repository.OAuthLoginCodeRedisRepository;
import com.planmate.domain.oauth.repository.OAuthSignupRedisRepository;
import com.planmate.domain.oauth.service.provider.OAuthProfileFetcher;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * provider별 HTTP 연동(토큰 교환 + 사용자정보 조회)은 {@code KakaoProfileFetcherTest} /
 * {@code GoogleProfileFetcherTest} / {@code NaverProfileFetcherTest}로 이동했다. 이 클래스는
 * {@link OAuthProfileFetcher}를 mock으로 대체하고, "기존유저 판별 / 탈퇴계정 재가입 / 이메일충돌 / 신규가입"이라는
 * 오케스트레이션 로직만 검증한다. 닉네임 정책({@link OAuthNicknamePolicy})은 mock하지 않고 실제 로직을
 * 그대로 태워서(userRepository만 mock) 원본 테스트의 검증 강도를 유지한다.
 */
@ExtendWith(MockitoExtension.class)
class OAuthLoginServiceTest {

    private static final String FRONTEND_REDIRECT_URI = "http://localhost:5173/oauth/callback";

    @Mock
    private OAuthProperties oAuthProperties;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OAuthLoginCodeRedisRepository oAuthLoginCodeRedisRepository;
    @Mock
    private OAuthSignupRedisRepository oAuthSignupRedisRepository;
    @Mock
    private OAuthProfileFetcher kakaoFetcher;
    @Mock
    private OAuthProfileFetcher googleFetcher;
    @Mock
    private OAuthProfileFetcher naverFetcher;

    private OAuthLoginService oAuthLoginService;

    @BeforeEach
    void setUp() {
        given(kakaoFetcher.supports()).willReturn(OAuthProvider.KAKAO);
        given(googleFetcher.supports()).willReturn(OAuthProvider.GOOGLE);
        given(naverFetcher.supports()).willReturn(OAuthProvider.NAVER);

        OAuthNicknamePolicy nicknamePolicy = new OAuthNicknamePolicy(userRepository);
        oAuthLoginService = new OAuthLoginService(oAuthProperties, userRepository, oAuthLoginCodeRedisRepository,
                oAuthSignupRedisRepository, nicknamePolicy,
                List.of(kakaoFetcher, googleFetcher, naverFetcher));
        oAuthLoginService.init();
    }

    private OAuthProperties.Provider kakaoProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("kakao-client-id");
        config.setAuthorizeUri("https://kauth.kakao.com/oauth/authorize");
        config.setScope("profile_nickname,account_email");
        return config;
    }

    private OAuthProperties.Provider googleProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("google-client-id");
        config.setAuthorizeUri("https://accounts.google.com/o/oauth2/v2/auth");
        config.setScope("openid profile email");
        return config;
    }

    private OAuthProperties.Provider naverProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("naver-client-id");
        config.setAuthorizeUri("https://nid.naver.com/oauth2.0/authorize");
        return config;
    }

    private User buildUser(UserProvider provider, String providerId, String nickname) {
        return User.builder()
                .userId(UUID.randomUUID())
                .provider(provider)
                .providerId(providerId)
                .email("existing@example.com")
                .nickname(nickname)
                .build();
    }

    @Test
    @DisplayName("콜백 성공 - 이미 SNS로 가입된 활성 유저 → 1회용 로그인 코드 발급 후 SUCCESS 리다이렉트")
    void handleCallback_existingActiveUser_issuesLoginCode() {
        given(kakaoFetcher.fetch(eq("auth-code"), any())).willReturn(new OAuthUserProfile("111", "user@example.com", "홍길동"));
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        User existingUser = buildUser(UserProvider.KAKAO, "111", "홍길동");
        given(userRepository.findByProviderAndProviderId(UserProvider.KAKAO, "111"))
                .willReturn(Optional.of(existingUser));
        given(oAuthLoginCodeRedisRepository.issue(existingUser.getUserId())).willReturn("login-code-abc");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.KAKAO, "auth-code", null);

        assertThat(redirectUrl).startsWith(FRONTEND_REDIRECT_URI);
        assertThat(redirectUrl).contains("status=SUCCESS").contains("code=login-code-abc");
        assertThat(existingUser.getDeletedAt()).isNull();
        verify(oAuthSignupRedisRepository, never()).save(any());
    }

    @Test
    @DisplayName("콜백 성공 - 탈퇴했던 SNS 계정으로 재로그인 → 활성 계정 조회에 안 잡혀 신규가입과 동일하게 가입캐시 저장")
    void handleCallback_deletedUser_treatedAsNewSignup() {
        given(kakaoFetcher.fetch(eq("auth-code"), any())).willReturn(new OAuthUserProfile("222", "user2@example.com", "라이언"));
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        // 탈퇴 계정은 @SQLRestriction이 적용된 findByProviderAndProviderId 조회에서 애초에 안 잡힌다
        given(userRepository.findByProviderAndProviderId(UserProvider.KAKAO, "222"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("user2@example.com")).willReturn(Optional.empty());
        given(userRepository.existsByNickname("라이언")).willReturn(false);
        given(oAuthSignupRedisRepository.save(any(OAuthSignupCache.class))).willReturn("signup-id-xyz");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.KAKAO, "auth-code", null);

        ArgumentCaptor<OAuthSignupCache> captor = ArgumentCaptor.forClass(OAuthSignupCache.class);
        verify(oAuthSignupRedisRepository).save(captor.capture());
        assertThat(captor.getValue().providerId()).isEqualTo("222");

        assertThat(redirectUrl).contains("status=NEED_ADDITIONAL_INFO").contains("signupId=signup-id-xyz");
        verify(oAuthLoginCodeRedisRepository, never()).issue(any());
    }

    @Test
    @DisplayName("콜백 정상 분기 - SNS 이메일이 이미 다른 provider 계정에 사용 중 → EMAIL_CONFLICT 리다이렉트, 가입캐시 저장 안 함")
    void handleCallback_emailConflict_redirectsFail() {
        given(kakaoFetcher.fetch(eq("auth-code"), any())).willReturn(new OAuthUserProfile("333", "conflict@example.com", "닉네임"));
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        given(userRepository.findByProviderAndProviderId(UserProvider.KAKAO, "333"))
                .willReturn(Optional.empty());
        User localUser = buildUser(UserProvider.LOCAL, null, "로컬유저");
        given(userRepository.findByEmailIgnoreCase("conflict@example.com"))
                .willReturn(Optional.of(localUser));

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.KAKAO, "auth-code", null);

        assertThat(redirectUrl).contains("status=FAIL").contains("reason=EMAIL_CONFLICT");
        verify(oAuthSignupRedisRepository, never()).save(any());
    }

    @Test
    @DisplayName("콜백 성공 - 신규 SNS 유저 → 닉네임 정제/중복회피 후 가입캐시 저장, NEED_ADDITIONAL_INFO 리다이렉트")
    void handleCallback_newUser_savesSignupCache() {
        given(kakaoFetcher.fetch(eq("auth-code"), any())).willReturn(new OAuthUserProfile("444", "newbie@example.com", " 홍 길동!! "));
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        given(userRepository.findByProviderAndProviderId(UserProvider.KAKAO, "444"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("newbie@example.com")).willReturn(Optional.empty());
        given(userRepository.existsByNickname("홍길동")).willReturn(false);
        given(oAuthSignupRedisRepository.save(any(OAuthSignupCache.class))).willReturn("signup-id-999");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.KAKAO, "auth-code", null);

        ArgumentCaptor<OAuthSignupCache> captor = ArgumentCaptor.forClass(OAuthSignupCache.class);
        verify(oAuthSignupRedisRepository).save(captor.capture());
        OAuthSignupCache cache = captor.getValue();

        assertThat(cache.provider()).isEqualTo(UserProvider.KAKAO);
        assertThat(cache.providerId()).isEqualTo("444");
        assertThat(cache.email()).isEqualTo("newbie@example.com");
        assertThat(cache.nickname()).isEqualTo("홍길동");

        assertThat(redirectUrl).contains("status=NEED_ADDITIONAL_INFO")
                .contains("signupId=signup-id-999")
                .contains("needEmail=false");
    }

    @Test
    @DisplayName("콜백 성공 - GOOGLE 기존 가입 유저 → fetchersByProvider가 GOOGLE을 GoogleProfileFetcher로 매핑 + SUCCESS 리다이렉트")
    void handleCallback_google_existingUser_issuesLoginCode() {
        given(googleFetcher.fetch(eq("auth-code"), any())).willReturn(new OAuthUserProfile("google-sub-111", "user@gmail.com", "구글유저"));
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        User existingUser = buildUser(UserProvider.GOOGLE, "google-sub-111", "구글유저");
        given(userRepository.findByProviderAndProviderId(UserProvider.GOOGLE, "google-sub-111"))
                .willReturn(Optional.of(existingUser));
        given(oAuthLoginCodeRedisRepository.issue(existingUser.getUserId())).willReturn("login-code-google");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.GOOGLE, "auth-code", null);

        assertThat(redirectUrl).contains("status=SUCCESS").contains("code=login-code-google");
    }

    @Test
    @DisplayName("콜백 성공 - GOOGLE 신규 유저 → 닉네임 정제되어 가입캐시 저장")
    void handleCallback_google_newUser_savesSignupCache() {
        given(googleFetcher.fetch(eq("auth-code"), any())).willReturn(new OAuthUserProfile("google-sub-222", "newbie@gmail.com", "Google User!!"));
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        given(userRepository.findByProviderAndProviderId(UserProvider.GOOGLE, "google-sub-222"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("newbie@gmail.com")).willReturn(Optional.empty());
        given(userRepository.existsByNickname("GoogleUser")).willReturn(false);
        given(oAuthSignupRedisRepository.save(any(OAuthSignupCache.class))).willReturn("signup-id-google");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.GOOGLE, "auth-code", null);

        ArgumentCaptor<OAuthSignupCache> captor = ArgumentCaptor.forClass(OAuthSignupCache.class);
        verify(oAuthSignupRedisRepository).save(captor.capture());
        OAuthSignupCache cache = captor.getValue();

        assertThat(cache.provider()).isEqualTo(UserProvider.GOOGLE);
        assertThat(cache.providerId()).isEqualTo("google-sub-222");
        assertThat(cache.email()).isEqualTo("newbie@gmail.com");
        assertThat(cache.nickname()).isEqualTo("GoogleUser");
        assertThat(redirectUrl).contains("status=NEED_ADDITIONAL_INFO").contains("signupId=signup-id-google");
    }

    @Test
    @DisplayName("콜백 성공 - NAVER 기존 가입 유저 → fetchersByProvider가 NAVER를 NaverProfileFetcher로 매핑 + SUCCESS 리다이렉트")
    void handleCallback_naver_existingUser_issuesLoginCode() {
        given(naverFetcher.fetch(eq("auth-code"), eq("csrf-state-abc")))
                .willReturn(new OAuthUserProfile("naver-333", "user@naver.com", "네이버유저"));
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        User existingUser = buildUser(UserProvider.NAVER, "naver-333", "네이버유저");
        given(userRepository.findByProviderAndProviderId(UserProvider.NAVER, "naver-333"))
                .willReturn(Optional.of(existingUser));
        given(oAuthLoginCodeRedisRepository.issue(existingUser.getUserId())).willReturn("login-code-naver");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.NAVER, "auth-code", "csrf-state-abc");

        assertThat(redirectUrl).contains("status=SUCCESS").contains("code=login-code-naver");
    }

    @Test
    @DisplayName("콜백 성공 - NAVER 신규 유저 → 가입캐시 저장, NEED_ADDITIONAL_INFO 리다이렉트")
    void handleCallback_naver_newUser_savesSignupCache() {
        given(naverFetcher.fetch(eq("auth-code"), eq("csrf-state-xyz")))
                .willReturn(new OAuthUserProfile("naver-444", "newbie@naver.com", "새유저"));
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        given(userRepository.findByProviderAndProviderId(UserProvider.NAVER, "naver-444"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("newbie@naver.com")).willReturn(Optional.empty());
        given(userRepository.existsByNickname("새유저")).willReturn(false);
        given(oAuthSignupRedisRepository.save(any(OAuthSignupCache.class))).willReturn("signup-id-naver");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.NAVER, "auth-code", "csrf-state-xyz");

        ArgumentCaptor<OAuthSignupCache> captor = ArgumentCaptor.forClass(OAuthSignupCache.class);
        verify(oAuthSignupRedisRepository).save(captor.capture());
        assertThat(captor.getValue().provider()).isEqualTo(UserProvider.NAVER);
        assertThat(captor.getValue().providerId()).isEqualTo("naver-444");
        assertThat(redirectUrl).contains("status=NEED_ADDITIONAL_INFO").contains("signupId=signup-id-naver");
    }

    @Test
    @DisplayName("로그인 시작 URL - KAKAO는 Controller가 만든 state를 쿼리파라미터에 포함하고, GOOGLE 전용 파라미터는 없음")
    void buildAuthorizeUrl_kakao_embedsControllerGeneratedState() {
        given(oAuthProperties.getProvider(OAuthProvider.KAKAO)).willReturn(kakaoProviderConfig());

        String url = oAuthLoginService.redirectToProvider(OAuthProvider.KAKAO, "csrf-state-kakao");

        assertThat(url).startsWith("https://kauth.kakao.com/oauth/authorize");
        assertThat(url).contains("client_id=kakao-client-id");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("state=csrf-state-kakao");
        assertThat(url).doesNotContain("access_type");
    }

    @Test
    @DisplayName("로그인 시작 URL - GOOGLE은 Controller가 만든 state에 더해 access_type=offline, prompt=consent 추가")
    void buildAuthorizeUrl_google_embedsControllerGeneratedState() {
        given(oAuthProperties.getProvider(OAuthProvider.GOOGLE)).willReturn(googleProviderConfig());

        String url = oAuthLoginService.redirectToProvider(OAuthProvider.GOOGLE, "csrf-state-google");

        assertThat(url).contains("state=csrf-state-google");
        assertThat(url).contains("access_type=offline").contains("prompt=consent");
    }

    @Test
    @DisplayName("로그인 시작 URL - NAVER는 Controller가 만든 state를 쿼리파라미터에 그대로 반영")
    void buildAuthorizeUrl_naver_embedsControllerGeneratedState() {
        given(oAuthProperties.getProvider(OAuthProvider.NAVER)).willReturn(naverProviderConfig());

        String url = oAuthLoginService.redirectToProvider(OAuthProvider.NAVER, "csrf-state-value");

        assertThat(url).contains("state=csrf-state-value");
    }
}
