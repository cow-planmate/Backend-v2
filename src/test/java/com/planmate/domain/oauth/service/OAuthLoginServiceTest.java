package com.planmate.domain.oauth.service;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthSignupCache;
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
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.domain.user.internal.outbox.UserOutboxWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OAuthLoginServiceTest {

    private static final String FRONTEND_REDIRECT_URI = "http://localhost:5173/oauth/callback";
    private static final String KAKAO_TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String KAKAO_USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";
    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USER_INFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final String NAVER_TOKEN_URI = "https://nid.naver.com/oauth2.0/token";
    private static final String NAVER_USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

    @Mock
    private OAuthProperties oAuthProperties;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OAuthLoginCodeRedisRepository oAuthLoginCodeRedisRepository;
    @Mock
    private OAuthSignupRedisRepository oAuthSignupRedisRepository;
    @Mock
    private RestTemplate restTemplate;

    @Mock
    private UserOutboxWriter userOutboxWriter;

    @InjectMocks
    private OAuthLoginService oAuthLoginService;

    private OAuthProperties.Provider kakaoProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("kakao-client-id");
        config.setClientSecret("kakao-secret");
        config.setRedirectUri("http://localhost:8080/api/oauth/kakao/callback");
        config.setAuthorizeUri("https://kauth.kakao.com/oauth/authorize");
        config.setTokenUri(KAKAO_TOKEN_URI);
        config.setUserInfoUri(KAKAO_USER_INFO_URI);
        config.setScope("profile_nickname,account_email");
        return config;
    }

    private OAuthProperties.Provider googleProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("google-client-id");
        config.setClientSecret("google-secret");
        config.setAuthorizeUri("https://accounts.google.com/o/oauth2/v2/auth");
        config.setRedirectUri("http://localhost:8080/api/oauth/google/callback");
        config.setTokenUri(GOOGLE_TOKEN_URI);
        config.setUserInfoUri(GOOGLE_USER_INFO_URI);
        config.setScope("openid profile email");
        return config;
    }

    private OAuthProperties.Provider naverProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("naver-client-id");
        config.setClientSecret("naver-secret");
        config.setAuthorizeUri("https://nid.naver.com/oauth2.0/authorize");
        config.setRedirectUri("http://localhost:8080/api/oauth/naver/callback");
        config.setTokenUri(NAVER_TOKEN_URI);
        config.setUserInfoUri(NAVER_USER_INFO_URI);
        return config;
    }

    /** KAKAO 프로필 조회(토큰 교환 + 사용자 정보 조회)를 stub. providerId는 KakaoUserResponse.id(Long) 규격상 숫자 문자열이어야 함. */
    private void stubKakaoProfileFetch(String providerId, String email, String rawNickname) {
        given(oAuthProperties.getProvider(OAuthProvider.KAKAO)).willReturn(kakaoProviderConfig());

        KakaoTokenResponse tokenResponse = new KakaoTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "kakao-access-token");
        given(restTemplate.postForObject(eq(KAKAO_TOKEN_URI), any(HttpEntity.class), eq(KakaoTokenResponse.class)))
                .willReturn(tokenResponse);

        KakaoUserResponse.KakaoAccount.Profile profile = new KakaoUserResponse.KakaoAccount.Profile();
        ReflectionTestUtils.setField(profile, "nickname", rawNickname);
        KakaoUserResponse.KakaoAccount account = new KakaoUserResponse.KakaoAccount();
        ReflectionTestUtils.setField(account, "email", email);
        ReflectionTestUtils.setField(account, "profile", profile);
        KakaoUserResponse userResponse = new KakaoUserResponse();
        ReflectionTestUtils.setField(userResponse, "id", Long.parseLong(providerId));
        ReflectionTestUtils.setField(userResponse, "kakaoAccount", account);

        given(restTemplate.exchange(eq(KAKAO_USER_INFO_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(KakaoUserResponse.class)))
                .willReturn(ResponseEntity.ok(userResponse));
    }

    /** GOOGLE 프로필 조회를 stub. GoogleUserResponse.sub/email/name 필드 매핑을 실제로 태운다. */
    private void stubGoogleProfileFetch(String providerId, String email, String name) {
        given(oAuthProperties.getProvider(OAuthProvider.GOOGLE)).willReturn(googleProviderConfig());

        GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "google-access-token");
        given(restTemplate.postForObject(eq(GOOGLE_TOKEN_URI), any(HttpEntity.class), eq(GoogleTokenResponse.class)))
                .willReturn(tokenResponse);

        GoogleUserResponse userResponse = new GoogleUserResponse();
        ReflectionTestUtils.setField(userResponse, "sub", providerId);
        ReflectionTestUtils.setField(userResponse, "email", email);
        ReflectionTestUtils.setField(userResponse, "name", name);

        given(restTemplate.exchange(eq(GOOGLE_USER_INFO_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(GoogleUserResponse.class)))
                .willReturn(ResponseEntity.ok(userResponse));
    }

    /**
     * NAVER 프로필 조회를 stub. NaverUserResponse.response(Result).id/email/nickname 필드 매핑과,
     * 토큰 교환 요청 바디에 state가 실제로 실려나가는지(11-4절)까지 검증한다.
     */
    private void stubNaverProfileFetch(String providerId, String email, String nickname, String expectedState) {
        given(oAuthProperties.getProvider(OAuthProvider.NAVER)).willReturn(naverProviderConfig());

        NaverTokenResponse tokenResponse = new NaverTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "naver-access-token");
        given(restTemplate.postForObject(eq(NAVER_TOKEN_URI),
                org.mockito.ArgumentMatchers.argThat((HttpEntity<?> entity) -> {
                    Object body = entity.getBody();
                    if (!(body instanceof org.springframework.util.MultiValueMap)) {
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    org.springframework.util.MultiValueMap<String, String> multiValueMap =
                            (org.springframework.util.MultiValueMap<String, String>) body;
                    return expectedState.equals(multiValueMap.getFirst("state"));
                }),
                eq(NaverTokenResponse.class)))
                .willReturn(tokenResponse);

        NaverUserResponse.Result result = new NaverUserResponse.Result();
        ReflectionTestUtils.setField(result, "id", providerId);
        ReflectionTestUtils.setField(result, "email", email);
        ReflectionTestUtils.setField(result, "nickname", nickname);
        NaverUserResponse userResponse = new NaverUserResponse();
        ReflectionTestUtils.setField(userResponse, "response", result);

        given(restTemplate.exchange(eq(NAVER_USER_INFO_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(NaverUserResponse.class)))
                .willReturn(ResponseEntity.ok(userResponse));
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
        stubKakaoProfileFetch("111", "user@example.com", "홍길동");
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        User existingUser = buildUser(UserProvider.KAKAO, "111", "홍길동");
        given(userRepository.findByProviderAndProviderIdIncludingDeleted("KAKAO", "111"))
                .willReturn(Optional.of(existingUser));
        given(oAuthLoginCodeRedisRepository.issue(existingUser.getUserId())).willReturn("login-code-abc");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.KAKAO, "auth-code", null);

        assertThat(redirectUrl).startsWith(FRONTEND_REDIRECT_URI);
        assertThat(redirectUrl).contains("status=SUCCESS").contains("code=login-code-abc");
        assertThat(existingUser.getDeletedAt()).isNull();
        verify(oAuthSignupRedisRepository, never()).save(any());
    }

    @Test
    @DisplayName("콜백 성공 - 탈퇴했던 SNS 계정으로 재로그인 → 복구되고, 닉네임이 선점됐으면 재확인 후 변경")
    void handleCallback_deletedUser_restoresAndReconfirmsNickname() {
        stubKakaoProfileFetch("222", "user2@example.com", "라이언");
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        User deletedUser = buildUser(UserProvider.KAKAO, "222", "라이언");
        deletedUser.softDelete();
        given(userRepository.findByProviderAndProviderIdIncludingDeleted("KAKAO", "222"))
                .willReturn(Optional.of(deletedUser));
        // 탈퇴해 있는 동안 다른 사람이 같은 닉네임을 선점한 상황을 재현
        given(userRepository.existsByNickname("라이언")).willReturn(true);
        given(userRepository.existsByNickname("라이언1")).willReturn(false);
        given(oAuthLoginCodeRedisRepository.issue(deletedUser.getUserId())).willReturn("login-code-xyz");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.KAKAO, "auth-code", null);

        assertThat(deletedUser.getDeletedAt()).isNull();
        assertThat(deletedUser.getNickname()).isEqualTo("라이언1");
        assertThat(redirectUrl).contains("status=SUCCESS").contains("code=login-code-xyz");
    }

    @Test
    @DisplayName("콜백 정상 분기 - SNS 이메일이 이미 다른 provider 계정에 사용 중 → EMAIL_CONFLICT 리다이렉트, 가입캐시 저장 안 함")
    void handleCallback_emailConflict_redirectsFail() {
        stubKakaoProfileFetch("333", "conflict@example.com", "닉네임");
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        given(userRepository.findByProviderAndProviderIdIncludingDeleted("KAKAO", "333"))
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
        stubKakaoProfileFetch("444", "newbie@example.com", " 홍 길동!! ");
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        given(userRepository.findByProviderAndProviderIdIncludingDeleted("KAKAO", "444"))
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
    @DisplayName("콜백 성공 - GOOGLE 기존 가입 유저 → GoogleUserResponse 필드 매핑 검증 + SUCCESS 리다이렉트")
    void handleCallback_google_existingUser_issuesLoginCode() {
        stubGoogleProfileFetch("google-sub-111", "user@gmail.com", "구글유저");
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        User existingUser = buildUser(UserProvider.GOOGLE, "google-sub-111", "구글유저");
        given(userRepository.findByProviderAndProviderIdIncludingDeleted("GOOGLE", "google-sub-111"))
                .willReturn(Optional.of(existingUser));
        given(oAuthLoginCodeRedisRepository.issue(existingUser.getUserId())).willReturn("login-code-google");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.GOOGLE, "auth-code", null);

        assertThat(redirectUrl).contains("status=SUCCESS").contains("code=login-code-google");
    }

    @Test
    @DisplayName("콜백 성공 - GOOGLE 신규 유저 → name 필드가 닉네임으로 정제되어 가입캐시 저장")
    void handleCallback_google_newUser_savesSignupCache() {
        stubGoogleProfileFetch("google-sub-222", "newbie@gmail.com", "Google User!!");
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        given(userRepository.findByProviderAndProviderIdIncludingDeleted("GOOGLE", "google-sub-222"))
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
    @DisplayName("콜백 성공 - NAVER 기존 가입 유저 → NaverUserResponse 필드 매핑 + state가 토큰 요청 바디에 실제로 실려감")
    void handleCallback_naver_existingUser_issuesLoginCode() {
        stubNaverProfileFetch("naver-333", "user@naver.com", "네이버유저", "csrf-state-abc");
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        User existingUser = buildUser(UserProvider.NAVER, "naver-333", "네이버유저");
        given(userRepository.findByProviderAndProviderIdIncludingDeleted("NAVER", "naver-333"))
                .willReturn(Optional.of(existingUser));
        given(oAuthLoginCodeRedisRepository.issue(existingUser.getUserId())).willReturn("login-code-naver");

        String redirectUrl = oAuthLoginService.callback(OAuthProvider.NAVER, "auth-code", "csrf-state-abc");

        assertThat(redirectUrl).contains("status=SUCCESS").contains("code=login-code-naver");
    }

    @Test
    @DisplayName("콜백 성공 - NAVER 신규 유저 → 가입캐시 저장, NEED_ADDITIONAL_INFO 리다이렉트")
    void handleCallback_naver_newUser_savesSignupCache() {
        stubNaverProfileFetch("naver-444", "newbie@naver.com", "새유저", "csrf-state-xyz");
        given(oAuthProperties.getFrontendRedirectUri()).willReturn(FRONTEND_REDIRECT_URI);

        given(userRepository.findByProviderAndProviderIdIncludingDeleted("NAVER", "naver-444"))
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
    @DisplayName("로그인 시작 URL - KAKAO는 부가 파라미터 없이 기본 쿼리만 구성")
    void buildAuthorizeUrl_kakao() {
        given(oAuthProperties.getProvider(OAuthProvider.KAKAO)).willReturn(kakaoProviderConfig());

        String url = oAuthLoginService.redirectToProvider(OAuthProvider.KAKAO, null);

        assertThat(url).startsWith("https://kauth.kakao.com/oauth/authorize");
        assertThat(url).contains("client_id=kakao-client-id");
        assertThat(url).contains("response_type=code");
        assertThat(url).doesNotContain("access_type").doesNotContain("state=");
    }

    @Test
    @DisplayName("로그인 시작 URL - GOOGLE은 access_type=offline, prompt=consent 추가")
    void buildAuthorizeUrl_google() {
        given(oAuthProperties.getProvider(OAuthProvider.GOOGLE)).willReturn(googleProviderConfig());

        String url = oAuthLoginService.redirectToProvider(OAuthProvider.GOOGLE, null);

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
