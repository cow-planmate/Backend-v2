package com.planmate.domain.oauth.service.provider;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.dto.naver.NaverTokenResponse;
import com.planmate.domain.oauth.dto.naver.NaverUserResponse;
import com.planmate.domain.oauth.enums.OAuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class NaverProfileFetcherTest {

    private static final String NAVER_TOKEN_URI = "https://nid.naver.com/oauth2.0/token";
    private static final String NAVER_USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

    @Mock
    private OAuthProperties oAuthProperties;
    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private NaverProfileFetcher naverProfileFetcher;

    private OAuthProperties.Provider naverProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("naver-client-id");
        config.setClientSecret("naver-secret");
        config.setRedirectUri("http://localhost:8080/api/oauth/naver/callback");
        config.setTokenUri(NAVER_TOKEN_URI);
        config.setUserInfoUri(NAVER_USER_INFO_URI);
        return config;
    }

    @Test
    @DisplayName("supports() - NAVER를 반환한다")
    void supports_returnsNaver() {
        assertThat(naverProfileFetcher.supports()).isEqualTo(OAuthProvider.NAVER);
    }

    @Test
    @DisplayName("fetch() - NaverUserResponse.response(Result) 필드 매핑 + state가 토큰 요청 바디에 실제로 실려간다(11-4절)")
    void fetch_mapsNaverUserResponseFields_andIncludesStateInTokenRequestBody() {
        given(oAuthProperties.getProvider(OAuthProvider.NAVER)).willReturn(naverProviderConfig());

        NaverTokenResponse tokenResponse = new NaverTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "naver-access-token");
        given(restTemplate.postForObject(eq(NAVER_TOKEN_URI),
                argThat((HttpEntity<?> entity) -> {
                    Object body = entity.getBody();
                    if (!(body instanceof MultiValueMap)) {
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    MultiValueMap<String, String> multiValueMap = (MultiValueMap<String, String>) body;
                    return "csrf-state-abc".equals(multiValueMap.getFirst("state"));
                }),
                eq(NaverTokenResponse.class)))
                .willReturn(tokenResponse);

        NaverUserResponse.Result result = new NaverUserResponse.Result();
        ReflectionTestUtils.setField(result, "id", "naver-333");
        ReflectionTestUtils.setField(result, "email", "user@naver.com");
        ReflectionTestUtils.setField(result, "nickname", "네이버유저");
        NaverUserResponse userResponse = new NaverUserResponse();
        ReflectionTestUtils.setField(userResponse, "response", result);

        given(restTemplate.exchange(eq(NAVER_USER_INFO_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(NaverUserResponse.class)))
                .willReturn(ResponseEntity.ok(userResponse));

        OAuthUserProfile profile = naverProfileFetcher.fetch("auth-code", "csrf-state-abc");

        assertThat(profile.getProviderId()).isEqualTo("naver-333");
        assertThat(profile.getEmail()).isEqualTo("user@naver.com");
        assertThat(profile.getNickname()).isEqualTo("네이버유저");
    }
}
