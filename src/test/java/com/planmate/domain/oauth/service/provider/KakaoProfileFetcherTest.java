package com.planmate.domain.oauth.service.provider;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.dto.kakao.KakaoTokenResponse;
import com.planmate.domain.oauth.dto.kakao.KakaoUserResponse;
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
class KakaoProfileFetcherTest {

    private static final String KAKAO_TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String KAKAO_USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    @Mock
    private OAuthProperties oAuthProperties;
    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private KakaoProfileFetcher kakaoProfileFetcher;

    private OAuthProperties.Provider kakaoProviderConfig() {
        OAuthProperties.Provider config = new OAuthProperties.Provider();
        config.setClientId("kakao-client-id");
        config.setClientSecret("kakao-secret");
        config.setRedirectUri("http://localhost:8080/api/oauth/kakao/callback");
        config.setTokenUri(KAKAO_TOKEN_URI);
        config.setUserInfoUri(KAKAO_USER_INFO_URI);
        return config;
    }

    @Test
    @DisplayName("supports() - KAKAO를 반환한다")
    void supports_returnsKakao() {
        assertThat(kakaoProfileFetcher.supports()).isEqualTo(OAuthProvider.KAKAO);
    }

    @Test
    @DisplayName("fetch() - 토큰 교환 후 사용자정보를 조회해 KakaoUserResponse 필드를 OAuthUserProfile로 매핑한다")
    void fetch_mapsKakaoUserResponseFields() {
        given(oAuthProperties.getProvider(OAuthProvider.KAKAO)).willReturn(kakaoProviderConfig());

        KakaoTokenResponse tokenResponse = new KakaoTokenResponse();
        ReflectionTestUtils.setField(tokenResponse, "accessToken", "kakao-access-token");
        given(restTemplate.postForObject(eq(KAKAO_TOKEN_URI), any(HttpEntity.class), eq(KakaoTokenResponse.class)))
                .willReturn(tokenResponse);

        KakaoUserResponse.KakaoAccount.Profile profile = new KakaoUserResponse.KakaoAccount.Profile();
        ReflectionTestUtils.setField(profile, "nickname", "홍길동");
        KakaoUserResponse.KakaoAccount account = new KakaoUserResponse.KakaoAccount();
        ReflectionTestUtils.setField(account, "email", "user@example.com");
        ReflectionTestUtils.setField(account, "profile", profile);
        KakaoUserResponse userResponse = new KakaoUserResponse();
        ReflectionTestUtils.setField(userResponse, "id", 111L);
        ReflectionTestUtils.setField(userResponse, "kakaoAccount", account);

        given(restTemplate.exchange(eq(KAKAO_USER_INFO_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(KakaoUserResponse.class)))
                .willReturn(ResponseEntity.ok(userResponse));

        OAuthUserProfile result = kakaoProfileFetcher.fetch("auth-code", null);

        assertThat(result.getProviderId()).isEqualTo("111");
        assertThat(result.getEmail()).isEqualTo("user@example.com");
        assertThat(result.getNickname()).isEqualTo("홍길동");
    }
}
