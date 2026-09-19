package com.planmate.domain.oauth.service.provider;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.dto.naver.NaverTokenResponse;
import com.planmate.domain.oauth.dto.naver.NaverUserResponse;
import com.planmate.domain.oauth.enums.OAuthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class NaverProfileFetcher implements OAuthProfileFetcher {

    private final OAuthProperties oAuthProperties;
    private final RestTemplate restTemplate;

    @Override
    public OAuthProvider supports() {
        return OAuthProvider.NAVER;
    }

    @Override
    public OAuthUserProfile fetch(String code, String state) {
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
                r.getNickname(),
                null   // 이번 범위에서 저장하지 않음(04_기능/OAuth/C_SNS_연동해제.md 0-3절)
        );
    }
}
