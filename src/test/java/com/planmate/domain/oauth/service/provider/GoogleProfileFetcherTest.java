package com.planmate.domain.oauth.service.provider;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.dto.google.GoogleTokenResponse;
import com.planmate.domain.oauth.dto.google.GoogleUserResponse;
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
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class GoogleProfileFetcherTest {

    private static final String GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USER_INFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo";

    @Mock
    private OAuthProperties oAuthProperties;
    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private GoogleProfileFetcher googleProfileFetcher;

    private OAuthProperties.Provider googleProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("google-client-id");
        config.setClientSecret("google-secret");
        config.setRedirectUri("http://localhost:8080/api/oauth/google/callback");
        config.setTokenUri(GOOGLE_TOKEN_URI);
        config.setUserInfoUri(GOOGLE_USER_INFO_URI);
        return config;
    }

    @Test
    @DisplayName("supports() - GOOGLE을 반환한다")
    void supports_returnsGoogle() {
        assertThat(googleProfileFetcher.supports()).isEqualTo(OAuthProvider.GOOGLE);
    }

    @Test
    @DisplayName("fetch() - 토큰 교환 후 사용자정보를 조회해 GoogleUserResponse.sub/email/name을 OAuthUserProfile로 매핑한다")
    void fetch_mapsGoogleUserResponseFields() {
        given(oAuthProperties.getProvider(OAuthProvider.GOOGLE)).willReturn(googleProviderConfig());

        GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "google-access-token");
        given(restTemplate.postForObject(eq(GOOGLE_TOKEN_URI), any(HttpEntity.class), eq(GoogleTokenResponse.class)))
                .willReturn(tokenResponse);

        GoogleUserResponse userResponse = new GoogleUserResponse();
        ReflectionTestUtils.setField(userResponse, "sub", "google-sub-111");
        ReflectionTestUtils.setField(userResponse, "email", "user@gmail.com");
        ReflectionTestUtils.setField(userResponse, "name", "구글유저");

        given(restTemplate.exchange(eq(GOOGLE_USER_INFO_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(GoogleUserResponse.class)))
                .willReturn(ResponseEntity.ok(userResponse));

        OAuthUserProfile result = googleProfileFetcher.fetch("auth-code", null);

        assertThat(result.getProviderId()).isEqualTo("google-sub-111");
        assertThat(result.getEmail()).isEqualTo("user@gmail.com");
        assertThat(result.getNickname()).isEqualTo("구글유저");
    }

    @Test
    @DisplayName("fetch() - 토큰 교환 응답의 refresh_token을 버리지 않고 OAuthUserProfile에 담는다 (C_SNS_연동해제.md 0-2절)")
    void fetch_propagatesRefreshToken() {
        given(oAuthProperties.getProvider(OAuthProvider.GOOGLE)).willReturn(googleProviderConfig());

        GoogleTokenResponse tokenResponse = new GoogleTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "google-access-token");
        ReflectionTestUtils.setField(tokenResponse, "refreshToken", "google-refresh-token");
        given(restTemplate.postForObject(eq(GOOGLE_TOKEN_URI), any(HttpEntity.class), eq(GoogleTokenResponse.class)))
                .willReturn(tokenResponse);

        GoogleUserResponse userResponse = new GoogleUserResponse();
        ReflectionTestUtils.setField(userResponse, "sub", "google-sub-111");
        ReflectionTestUtils.setField(userResponse, "email", "user@gmail.com");
        ReflectionTestUtils.setField(userResponse, "name", "구글유저");
        given(restTemplate.exchange(eq(GOOGLE_USER_INFO_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(GoogleUserResponse.class)))
                .willReturn(ResponseEntity.ok(userResponse));

        OAuthUserProfile result = googleProfileFetcher.fetch("auth-code", null);

        assertThat(result.getRefreshToken()).isEqualTo("google-refresh-token");
    }
}
