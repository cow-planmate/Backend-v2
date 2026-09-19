package com.planmate.domain.oauth.service.provider;

import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.security.SnsRefreshTokenCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Google은 revoke에 access/refresh token 중 하나가 반드시 필요하다(다른 방법 없음) —
 * 04_기능/OAuth/C_SNS_연동해제.md 0-3절/5-2절 참고.
 */
@Component
@RequiredArgsConstructor
public class GoogleUnlinkClient implements SnsUnlinkClient {

    private static final String REVOKE_URI = "https://oauth2.googleapis.com/revoke";

    private final SnsRefreshTokenCipher snsRefreshTokenCipher;
    private final RestTemplate restTemplate;

    @Override
    public UserProvider supports() {
        return UserProvider.GOOGLE;
    }

    @Override
    public void unlink(User user) {
        String encrypted = user.getSnsRefreshToken();
        if (encrypted == null) {
            return;   // 저장된 토큰이 없으면 할 일 없음 (2-1절 4번 — 이례적인 케이스)
        }
        String refreshToken = snsRefreshTokenCipher.decrypt(encrypted);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("token", refreshToken);

        restTemplate.postForEntity(REVOKE_URI, new HttpEntity<>(body, headers), Void.class);
    }
}
