package com.planmate.domain.oauth.service.provider;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.planmate.common.exception.domain.OAuthInvalidIdTokenException;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.planmate.domain.oauth.config.OAuthProperties;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/** 서명·aud·iss·exp 검증 자체는 구글 라이브러리 몫이라 mock으로 대체하고, 결과 해석만 검증한다. */
@ExtendWith(MockitoExtension.class)
class GoogleIdTokenProfileVerifierTest {

    @Mock
    private GoogleIdTokenVerifier googleVerifier;

    private GoogleIdToken tokenWith(String sub, String email, Boolean emailVerified, String name) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject(sub);
        payload.setEmail(email);
        payload.setEmailVerified(emailVerified);
        payload.set("name", name);
        return new GoogleIdToken(new JsonWebSignature.Header(), payload, new byte[0], new byte[0]);
    }

    @Test
    @DisplayName("검증 성공 - sub/email/name을 프로필로, refresh_token은 없음(null)")
    void verify_success() throws Exception {
        given(googleVerifier.verify("id-token")).willReturn(tokenWith("google-sub-1", "a@example.com", true, "홍길동"));

        OAuthUserProfile profile = new GoogleIdTokenProfileVerifier(googleVerifier).verify("id-token");

        assertThat(profile.getProviderId()).isEqualTo("google-sub-1");
        assertThat(profile.getEmail()).isEqualTo("a@example.com");
        assertThat(profile.getNickname()).isEqualTo("홍길동");
        assertThat(profile.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("email_verified가 true가 아니면 이메일을 버린다(null) — 추가정보 입력에서 받게")
    void verify_unverifiedEmail_dropped() throws Exception {
        given(googleVerifier.verify("id-token")).willReturn(tokenWith("google-sub-2", "b@example.com", false, "라이언"));

        OAuthUserProfile profile = new GoogleIdTokenProfileVerifier(googleVerifier).verify("id-token");

        assertThat(profile.getEmail()).isNull();
    }

    @Test
    @DisplayName("검증 실패(서명·aud·만료 등) - 라이브러리가 null → OAUTH_006")
    void verify_invalid_throws() throws Exception {
        given(googleVerifier.verify("bad-token")).willReturn(null);

        assertThatThrownBy(() -> new GoogleIdTokenProfileVerifier(googleVerifier).verify("bad-token"))
                .isInstanceOf(OAuthInvalidIdTokenException.class);
    }

    @Test
    @DisplayName("JWT 형식이 아님 - 파싱 실패도 OAUTH_006")
    void verify_malformed_throws() throws Exception {
        given(googleVerifier.verify("not-a-jwt")).willThrow(new IllegalArgumentException());

        assertThatThrownBy(() -> new GoogleIdTokenProfileVerifier(googleVerifier).verify("not-a-jwt"))
                .isInstanceOf(OAuthInvalidIdTokenException.class);
    }

    @Test
    @DisplayName("웹 클라이언트 ID가 비었거나 google 설정 자체가 없어도 기동은 되고, 검증은 OAUTH_006으로 실패")
    void verify_missingWebClientId_failsWithoutCrash() {
        OAuthProperties blank = new OAuthProperties();
        OAuthProperties.Provider google = new OAuthProperties.Provider();
        google.setClientId("");
        blank.setProvider(Map.of("google", google));
        OAuthProperties noGoogle = new OAuthProperties();
        noGoogle.setProvider(Map.of());
        OAuthProperties noProviders = new OAuthProperties();

        for (OAuthProperties props : List.of(blank, noGoogle, noProviders)) {
            assertThatThrownBy(() -> new GoogleIdTokenProfileVerifier(props).verify("id-token"))
                    .isInstanceOf(OAuthInvalidIdTokenException.class);
        }
    }

    @Test
    @DisplayName("공개키 조회 실패 등 서버 쪽 문제 - 토큰 무효가 아니라 IllegalStateException(500)")
    void verify_ioFailure_isServerError() throws Exception {
        given(googleVerifier.verify("id-token")).willThrow(new IOException("network"));

        assertThatThrownBy(() -> new GoogleIdTokenProfileVerifier(googleVerifier).verify("id-token"))
                .isInstanceOf(IllegalStateException.class);
    }
}
