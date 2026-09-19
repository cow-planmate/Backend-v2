package com.planmate.domain.oauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.auth.repository.RefreshTokenRedisRepository;
import com.planmate.domain.oauth.dto.OAuthCompleteRequest;
import com.planmate.domain.oauth.dto.OAuthExchangeRequest;
import com.planmate.domain.oauth.dto.OAuthSignupCache;
import com.planmate.domain.oauth.dto.google.GoogleTokenResponse;
import com.planmate.domain.oauth.dto.google.GoogleUserResponse;
import com.planmate.domain.oauth.dto.kakao.KakaoTokenResponse;
import com.planmate.domain.oauth.dto.kakao.KakaoUserResponse;
import com.planmate.domain.oauth.dto.naver.NaverTokenResponse;
import com.planmate.domain.oauth.dto.naver.NaverUserResponse;
import com.planmate.domain.oauth.repository.OAuthLoginCodeRedisRepository;
import com.planmate.domain.oauth.repository.OAuthSignupRedisRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OAuthControllerTest {

    private static final String KAKAO_TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String KAKAO_USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";
    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USER_INFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final String NAVER_TOKEN_URI = "https://nid.naver.com/oauth2.0/token";
    private static final String NAVER_USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OAuthLoginCodeRedisRepository oAuthLoginCodeRedisRepository;
    @Autowired
    private OAuthSignupRedisRepository oAuthSignupRedisRepository;
    @Autowired
    private RefreshTokenRedisRepository refreshTokenRedisRepository;

    // 실제 카카오/구글/네이버 서버로 나가는 외부 HTTP 호출만 목킹 — 그 외(DB/Redis/JWT)는 전부 실제로 붙는다
    @MockitoBean
    private RestTemplate restTemplate;

    private String leftoverSignupId;

    @AfterEach
    void tearDown() {
        // DB 쓰기는 @Transactional로 롤백되지만 Redis는 같은 트랜잭션에 묶이지 않으므로 직접 정리
        if (leftoverSignupId != null) {
            oAuthSignupRedisRepository.delete(leftoverSignupId);
        }
    }

    private void stubKakaoProfile(String providerId, String email, String nickname) {
        KakaoTokenResponse tokenResponse = new KakaoTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "kakao-access-token");
        given(restTemplate.postForObject(eq(KAKAO_TOKEN_URI), any(HttpEntity.class), eq(KakaoTokenResponse.class)))
                .willReturn(tokenResponse);

        KakaoUserResponse.KakaoAccount.Profile profile = new KakaoUserResponse.KakaoAccount.Profile();
        ReflectionTestUtils.setField(profile, "nickname", nickname);
        KakaoUserResponse.KakaoAccount account = new KakaoUserResponse.KakaoAccount();
        ReflectionTestUtils.setField(account, "email", email);
        ReflectionTestUtils.setField(account, "profile", profile);
        KakaoUserResponse userResponse = new KakaoUserResponse();
        ReflectionTestUtils.setField(userResponse, "id", Long.parseLong(providerId));
        ReflectionTestUtils.setField(userResponse, "kakaoAccount", account);

        given(restTemplate.exchange(eq(KAKAO_USER_INFO_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(KakaoUserResponse.class)))
                .willReturn(ResponseEntity.ok(userResponse));
    }

    private void stubGoogleProfile(String providerId, String email, String name) {
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

    private void stubNaverProfile(String providerId, String email, String nickname) {
        NaverTokenResponse tokenResponse = new NaverTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "naver-access-token");
        given(restTemplate.postForObject(eq(NAVER_TOKEN_URI), any(HttpEntity.class), eq(NaverTokenResponse.class)))
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

    private Map<String, String> parseQueryParams(String redirectUrl) {
        return UriComponentsBuilder.fromUriString(redirectUrl).build().getQueryParams().toSingleValueMap();
    }

    // 로그인 시작을 실제로 호출해서 진짜 state 쿠키를 발급받는다(더블 서브밋 흐름 재현) — 모든 provider가 공통으로 CSRF 검증을 받으므로 콜백 테스트마다 재사용
    private jakarta.servlet.http.Cookie startAndGetStateCookie(String providerPath) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/oauth/" + providerPath))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists("oauth_state"))
                .andReturn();
        return result.getResponse().getCookie("oauth_state");
    }

    // providerId는 KakaoUserResponse.id(Long) 규격에 맞춰 숫자 문자열로만 생성(stubKakaoProfile이 Long.parseLong으로 파싱)
    private String randomNumericProviderId() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));
    }

    private User saveActiveKakaoUser() {
        return userRepository.save(User.builder()
                .provider(UserProvider.KAKAO)
                .providerId(randomNumericProviderId())
                .email("oauth-it-" + UUID.randomUUID() + "@example.com")
                .nickname("oauthit" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
    }

    // ===================== 1) SNS 로그인 시작 =====================

    @Test
    @DisplayName("로그인 시작 성공 - KAKAO는 인가 페이지로 302 리다이렉트, state 쿠키도 인가 URL과 동일하게 포함(더블 서브밋)")
    void redirectToProvider_kakao_setsStateCookieMatchingAuthorizeUrl() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/oauth/kakao"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://kauth.kakao.com/oauth/authorize")))
                .andExpect(header().string("Location", containsString("client_id=")))
                .andExpect(cookie().exists("oauth_state"))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        String stateCookieValue = result.getResponse().getCookie("oauth_state").getValue();
        assertThat(location).contains("state=" + stateCookieValue);
    }

    @Test
    @DisplayName("로그인 시작 성공 - GOOGLE은 인가 페이지로 302 리다이렉트, state 쿠키도 인가 URL과 동일하게 포함(더블 서브밋)")
    void redirectToProvider_google_setsStateCookieMatchingAuthorizeUrl() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/oauth/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("access_type=offline")))
                .andExpect(cookie().exists("oauth_state"))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        String stateCookieValue = result.getResponse().getCookie("oauth_state").getValue();
        assertThat(location).contains("state=" + stateCookieValue);
    }

    @Test
    @DisplayName("로그인 시작 성공 - NAVER는 state 쿠키를 심고 인가 URL에도 동일한 state를 포함(더블 서브밋)")
    void redirectToProvider_naver_setsStateCookieMatchingAuthorizeUrl() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/oauth/naver"))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists("oauth_state"))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        String stateCookieValue = result.getResponse().getCookie("oauth_state").getValue();
        assertThat(location).contains("state=" + stateCookieValue);
    }

    @Test
    @DisplayName("로그인 시작 실패 - 지원 안 하는 provider면 프론트 실패 URL로 리다이렉트(JSON 에러 아님)")
    void redirectToProvider_unsupportedProvider_redirectsToFailUrl() throws Exception {
        mockMvc.perform(get("/api/oauth/local"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", allOf(
                        startsWith("http://localhost:5173/oauth/callback"),
                        containsString("status=FAIL"),
                        containsString("reason=UNSUPPORTED_PROVIDER"))));
    }

    // ===================== 2) SNS 로그인 콜백 =====================

    @Test
    @DisplayName("콜백 성공 - 기존 가입 유저 → SUCCESS 리다이렉트 + 실제 Redis에서 로그인코드로 userId 조회 가능")
    void callback_existingUser_redirectsWithConsumableLoginCode() throws Exception {
        User existingUser = saveActiveKakaoUser();
        stubKakaoProfile(existingUser.getProviderId(), existingUser.getEmail(), existingUser.getNickname());
        jakarta.servlet.http.Cookie stateCookie = startAndGetStateCookie("kakao");

        MvcResult result = mockMvc.perform(get("/api/oauth/kakao/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", stateCookie.getValue())
                        .cookie(stateCookie))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        Map<String, String> params = parseQueryParams(location);
        assertThat(params.get("status")).isEqualTo("SUCCESS");

        // 실제 Redis에서 소비까지 해봐서 "코드가 존재한다"가 아니라 "그 코드로 실제 userId를 얻을 수 있다"를 검증
        assertThat(oAuthLoginCodeRedisRepository.consume(params.get("code"))).contains(existingUser.getUserId());
    }

    @Test
    @DisplayName("OAuthLoginCodeRedisRepository.consume()는 1회용 - 같은 코드로 두 번째 호출하면 빈 값(getAndDelete 원자성 회귀 테스트)")
    void oAuthLoginCodeRedisRepository_consume_isSingleUse() {
        User user = saveActiveKakaoUser();
        String code = oAuthLoginCodeRedisRepository.issue(user.getUserId());

        assertThat(oAuthLoginCodeRedisRepository.consume(code)).contains(user.getUserId());
        assertThat(oAuthLoginCodeRedisRepository.consume(code)).isEmpty();
    }

    @Test
    @DisplayName("콜백 성공 - 탈퇴했던 SNS 계정으로 재로그인 → 활성 계정 조회에서 안 잡혀 신규가입과 동일하게 NEED_ADDITIONAL_INFO")
    void callback_deletedUser_treatedAsNewSignupAgainstRealDb() throws Exception {
        String oldNickname = "탈퇴닉" + UUID.randomUUID().toString().substring(0, 6);
        String providerId = randomNumericProviderId();

        User deletedUser = userRepository.save(User.builder()
                .provider(UserProvider.KAKAO)
                .providerId(providerId)
                .email("rejoin-it-" + UUID.randomUUID() + "@example.com")
                .nickname(oldNickname)
                .role(UserRole.USER)
                .build());
        deletedUser.softDelete();
        userRepository.saveAndFlush(deletedUser);

        // 탈퇴 계정과 완전히 동일한 provider+providerId로 다시 로그인
        stubKakaoProfile(providerId, "rejoin-new-" + UUID.randomUUID() + "@example.com", oldNickname);
        jakarta.servlet.http.Cookie stateCookie = startAndGetStateCookie("kakao");

        MvcResult result = mockMvc.perform(get("/api/oauth/kakao/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", stateCookie.getValue())
                        .cookie(stateCookie))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        Map<String, String> params = parseQueryParams(result.getResponse().getHeader("Location"));
        // 탈퇴 계정은 복구되지 않고 신규가입 분기로 흘러 추가정보 입력을 요구한다
        assertThat(params.get("status")).isEqualTo("NEED_ADDITIONAL_INFO");
        assertThat(params.get("signupId")).isNotBlank();

        // 탈퇴한 원래 행은 그대로 탈퇴 상태로 남아있다(복구되지 않음)
        // 같은 트랜잭션 안이라 findById가 1차 캐시의 관리 중인 인스턴스를 그대로 돌려주므로(SQLRestriction 우회),
        // "조회가 안 됨"이 아니라 "deletedAt이 여전히 채워져 있음"으로 복구 여부를 확인한다.
        assertThat(userRepository.findById(deletedUser.getUserId()))
                .hasValueSatisfying(user -> assertThat(user.getDeletedAt()).isNotNull());
    }

    @Test
    @DisplayName("콜백 정상 분기 - SNS 이메일이 실제 DB의 다른 LOCAL 계정과 충돌 → EMAIL_CONFLICT 리다이렉트")
    void callback_emailConflict_withRealLocalAccount() throws Exception {
        String conflictingEmail = "conflict-it-" + UUID.randomUUID() + "@example.com";
        userRepository.save(User.builder()
                .provider(UserProvider.LOCAL)
                .email(conflictingEmail)
                .password("encoded-password")
                .nickname("localuser" + UUID.randomUUID().toString().substring(0, 6))
                .role(UserRole.USER)
                .build());

        stubKakaoProfile(randomNumericProviderId(), conflictingEmail, "카카오유저");
        jakarta.servlet.http.Cookie stateCookie = startAndGetStateCookie("kakao");

        mockMvc.perform(get("/api/oauth/kakao/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", stateCookie.getValue())
                        .cookie(stateCookie))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("reason=EMAIL_CONFLICT")));
    }

    @Test
    @DisplayName("콜백 성공 - 신규 SNS 유저 → NEED_ADDITIONAL_INFO 리다이렉트 + 실제 Redis에 가입캐시 저장 확인")
    void callback_newUser_redirectsWithSignupCache() throws Exception {
        String providerId = randomNumericProviderId();
        String email = "new-kakao-" + UUID.randomUUID() + "@example.com";
        stubKakaoProfile(providerId, email, "신규유저!!");
        jakarta.servlet.http.Cookie stateCookie = startAndGetStateCookie("kakao");

        MvcResult result = mockMvc.perform(get("/api/oauth/kakao/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", stateCookie.getValue())
                        .cookie(stateCookie))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        Map<String, String> params = parseQueryParams(location);
        assertThat(params.get("status")).isEqualTo("NEED_ADDITIONAL_INFO");
        assertThat(params.get("needEmail")).isEqualTo("false");

        leftoverSignupId = params.get("signupId");
        OAuthSignupCache cache = oAuthSignupRedisRepository.find(leftoverSignupId).orElseThrow();
        assertThat(cache.provider()).isEqualTo(UserProvider.KAKAO);
        assertThat(cache.providerId()).isEqualTo(providerId);
        assertThat(cache.email()).isEqualTo(email);
        assertThat(cache.nickname()).isEqualTo("신규유저"); // sanitizeNickname이 특수문자 제거
    }

    @Test
    @DisplayName("콜백 성공 - GOOGLE 기존 가입 유저 → GoogleUserResponse 필드 매핑까지 실제 HTTP/DB로 검증")
    void callback_google_existingUser_redirectsWithConsumableLoginCode() throws Exception {
        User existingUser = userRepository.save(User.builder()
                .provider(UserProvider.GOOGLE)
                .providerId("google-sub-" + UUID.randomUUID())
                .email("google-it-" + UUID.randomUUID() + "@example.com")
                .nickname("googleit" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        stubGoogleProfile(existingUser.getProviderId(), existingUser.getEmail(), existingUser.getNickname());
        jakarta.servlet.http.Cookie stateCookie = startAndGetStateCookie("google");

        MvcResult result = mockMvc.perform(get("/api/oauth/google/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", stateCookie.getValue())
                        .cookie(stateCookie))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        Map<String, String> params = parseQueryParams(result.getResponse().getHeader("Location"));
        assertThat(params.get("status")).isEqualTo("SUCCESS");
        assertThat(oAuthLoginCodeRedisRepository.consume(params.get("code"))).contains(existingUser.getUserId());
    }

    @Test
    @DisplayName("콜백 성공 - NAVER 기존 가입 유저 → 실제 state 쿠키 발급 후 콜백까지 이어서 검증")
    void callback_naver_existingUser_redirectsWithConsumableLoginCode() throws Exception {
        // 1) 실제 /api/oauth/naver를 먼저 호출해서 진짜 state 쿠키를 발급받는다(더블 서브밋 흐름 그대로 재현)
        MvcResult startResult = mockMvc.perform(get("/api/oauth/naver"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        jakarta.servlet.http.Cookie stateCookie = startResult.getResponse().getCookie("oauth_state");
        String state = stateCookie.getValue();

        User existingUser = userRepository.save(User.builder()
                .provider(UserProvider.NAVER)
                .providerId("naver-" + UUID.randomUUID())
                .email("naver-it-" + UUID.randomUUID() + "@example.com")
                .nickname("naverit" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        stubNaverProfile(existingUser.getProviderId(), existingUser.getEmail(), existingUser.getNickname());

        // 2) 발급받은 쿠키를 그대로 실어서 콜백 호출 — CSRF 검증 통과 후 실제 fetchNaverProfile까지 탄다
        MvcResult callbackResult = mockMvc.perform(get("/api/oauth/naver/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", state)
                        .cookie(stateCookie))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        Map<String, String> params = parseQueryParams(callbackResult.getResponse().getHeader("Location"));
        assertThat(params.get("status")).isEqualTo("SUCCESS");
        assertThat(oAuthLoginCodeRedisRepository.consume(params.get("code"))).contains(existingUser.getUserId());
    }

    @Test
    @DisplayName("콜백 실패 - NAVER state 쿠키 없이 호출하면 INVALID_STATE 리다이렉트(Service 호출 자체를 안 함)")
    void callback_naverWithoutStateCookie_redirectsInvalidState() throws Exception {
        mockMvc.perform(get("/api/oauth/naver/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", "some-state-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("reason=INVALID_STATE")));
    }

    @Test
    @DisplayName("콜백 실패 - KAKAO도 state 쿠키 없이 호출하면 INVALID_STATE 리다이렉트(NAVER 전용이 아니라 전체 provider에 적용됨)")
    void callback_kakaoWithoutStateCookie_redirectsInvalidState() throws Exception {
        mockMvc.perform(get("/api/oauth/kakao/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", "some-state-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("reason=INVALID_STATE")));
    }

    @Test
    @DisplayName("콜백 실패 - KAKAO state 쿠키는 있지만 쿼리파라미터 state와 값이 다르면 INVALID_STATE 리다이렉트")
    void callback_kakaoStateMismatch_redirectsInvalidState() throws Exception {
        jakarta.servlet.http.Cookie stateCookie = startAndGetStateCookie("kakao");

        mockMvc.perform(get("/api/oauth/kakao/callback")
                        .param("code", "dummy-auth-code")
                        .param("state", "different-state-value")
                        .cookie(stateCookie))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("reason=INVALID_STATE")));
    }

    @Test
    @DisplayName("콜백 실패 - 지원 안 하는 provider면 UNSUPPORTED_PROVIDER 리다이렉트")
    void callback_unsupportedProvider_redirectsToFailUrl() throws Exception {
        mockMvc.perform(get("/api/oauth/local/callback").param("code", "dummy-auth-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("reason=UNSUPPORTED_PROVIDER")));
    }

    // ===================== 3) 인가코드 교환 =====================

    @Test
    @DisplayName("인가코드 교환 성공 - 200, 토큰 발급 및 실제 Redis에 Refresh Token 저장 확인")
    void exchange_success() throws Exception {
        User existingUser = saveActiveKakaoUser();
        String loginCode = oAuthLoginCodeRedisRepository.issue(existingUser.getUserId());

        String responseBody = mockMvc.perform(post("/api/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthExchangeRequest(loginCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.userId").value(existingUser.getUserId().toString()))
                .andReturn().getResponse().getContentAsString();

        String refreshToken = objectMapper.readTree(responseBody).get("refreshToken").asText();
        assertThat(refreshTokenRedisRepository.findUserId(refreshToken)).contains(existingUser.getUserId());
    }

    @Test
    @DisplayName("인가코드 교환 실패 - 존재하지 않는/만료된 코드는 401, AUTH_001")
    void exchange_fail_invalidCode() throws Exception {
        mockMvc.perform(post("/api/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthExchangeRequest("nonexistent-code"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    @Test
    @DisplayName("인가코드 교환 검증 실패 - code가 빈 값이면 400, COMMON_001")
    void exchange_validationFailure_blankCode() throws Exception {
        mockMvc.perform(post("/api/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthExchangeRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    // ===================== 4) 가입 완료 =====================

    @Test
    @DisplayName("가입 완료 성공 - 201, User 실제 저장 확인 + 토큰 발급")
    void complete_success() throws Exception {
        OAuthSignupCache cache = new OAuthSignupCache(
                UserProvider.KAKAO, "kakao-" + UUID.randomUUID(),
                "complete-it-" + UUID.randomUUID() + "@example.com", "완료테스트" + UUID.randomUUID().toString().substring(0, 6),
                null);
        String signupId = oAuthSignupRedisRepository.save(cache);

        OAuthCompleteRequest request = new OAuthCompleteRequest(signupId, null, LocalDate.of(2000, 3, 15), Gender.FEMALE);

        String responseBody = mockMvc.perform(post("/api/oauth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.email").value(cache.email()))
                .andReturn().getResponse().getContentAsString();

        UUID userId = UUID.fromString(objectMapper.readTree(responseBody).get("userId").asText());
        User savedUser = userRepository.findById(userId).orElseThrow();
        assertThat(savedUser.getProvider()).isEqualTo(UserProvider.KAKAO);
        assertThat(savedUser.getProviderId()).isEqualTo(cache.providerId());
        assertThat(savedUser.getNickname()).isEqualTo(cache.nickname());
        assertThat(savedUser.getGender()).isEqualTo(Gender.FEMALE);

        // 1회용 가입캐시가 실제로 삭제됐는지까지 확인
        assertThat(oAuthSignupRedisRepository.find(signupId)).isEmpty();
    }

    @Test
    @DisplayName("가입 완료 실패 - signupId로 가입 세션을 찾을 수 없으면 404, OAUTH_002")
    void complete_fail_signupSessionNotFound() throws Exception {
        OAuthCompleteRequest request = new OAuthCompleteRequest("nonexistent-signup-id", null, LocalDate.of(2000, 1, 1), Gender.MALE);

        mockMvc.perform(post("/api/oauth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OAUTH_002"));
    }

    @Test
    @DisplayName("가입 완료 검증 실패 - signupId가 빈 값이면 400, COMMON_001")
    void complete_validationFailure_blankSignupId() throws Exception {
        OAuthCompleteRequest request = new OAuthCompleteRequest("", null, LocalDate.of(2000, 1, 1), Gender.MALE);

        mockMvc.perform(post("/api/oauth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("가입 완료 실패 - 이미 같은 provider+providerId로 가입된 계정이 있으면(레이스) 409, OAUTH_004")
    void complete_fail_alreadyRegistered_realDbUniqueConstraintGuardCheck() throws Exception {
        String providerId = "kakao-" + UUID.randomUUID();
        userRepository.save(User.builder()
                .provider(UserProvider.KAKAO)
                .providerId(providerId)
                .email("already-" + UUID.randomUUID() + "@example.com")
                .nickname("already" + UUID.randomUUID().toString().substring(0, 6))
                .role(UserRole.USER)
                .build());

        OAuthSignupCache cache = new OAuthSignupCache(
                UserProvider.KAKAO, providerId,
                "complete-race-" + UUID.randomUUID() + "@example.com", "race" + UUID.randomUUID().toString().substring(0, 6),
                null);
        String signupId = oAuthSignupRedisRepository.save(cache);
        leftoverSignupId = signupId; // 이 케이스는 실패해서 Service가 delete()까지 못 감 — 직접 정리 필요

        OAuthCompleteRequest request = new OAuthCompleteRequest(signupId, null, LocalDate.of(2000, 1, 1), Gender.MALE);

        mockMvc.perform(post("/api/oauth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OAUTH_004"));
    }

    @Test
    @DisplayName("가입 완료 성공 - 탈퇴한 계정과 동일한 provider+providerId로 새 계정 INSERT, 실제 uk_users_provider_provider_id(V14) 위반 없음")
    void complete_success_reusesDeletedAccountProviderIdAgainstRealDb() throws Exception {
        String providerId = "kakao-" + UUID.randomUUID();

        User deletedUser = userRepository.save(User.builder()
                .provider(UserProvider.KAKAO)
                .providerId(providerId)
                .email("old-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("old" + UUID.randomUUID().toString().substring(0, 6))
                .role(UserRole.USER)
                .build());
        deletedUser.softDelete();
        userRepository.saveAndFlush(deletedUser);

        OAuthSignupCache cache = new OAuthSignupCache(
                UserProvider.KAKAO, providerId,
                "new-owner-" + UUID.randomUUID() + "@example.com", "new" + UUID.randomUUID().toString().substring(0, 6),
                null);
        String signupId = oAuthSignupRedisRepository.save(cache);

        OAuthCompleteRequest request = new OAuthCompleteRequest(signupId, null, LocalDate.of(2001, 5, 20), Gender.MALE);

        String responseBody = mockMvc.perform(post("/api/oauth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID newUserId = UUID.fromString(objectMapper.readTree(responseBody).get("userId").asText());
        assertThat(newUserId).isNotEqualTo(deletedUser.getUserId());

        User newUser = userRepository.findById(newUserId).orElseThrow();
        assertThat(newUser.getProviderId()).isEqualTo(providerId);
        assertThat(newUser.getDeletedAt()).isNull();
        assertThat(newUser.getEmail()).isEqualTo(cache.email());
    }
}
