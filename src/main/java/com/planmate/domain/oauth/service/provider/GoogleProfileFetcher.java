package com.planmate.domain.oauth.service.provider;

import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.dto.google.GoogleTokenResponse;
import com.planmate.domain.oauth.dto.google.GoogleUserResponse;
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
public class GoogleProfileFetcher implements OAuthProfileFetcher {

    private final OAuthProperties oAuthProperties;
    private final RestTemplate restTemplate;

    @Override
    public OAuthProvider supports() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public OAuthUserProfile fetch(String code, String state) {
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
}
