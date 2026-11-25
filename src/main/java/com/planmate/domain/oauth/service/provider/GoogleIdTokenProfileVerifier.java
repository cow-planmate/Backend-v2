package com.planmate.domain.oauth.service.provider;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.planmate.common.exception.domain.OAuthInvalidIdTokenException;
import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.enums.OAuthProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * 앱(Android Credential Manager)이 보낸 구글 ID 토큰을 검증해 프로필로 바꾼다.
 *
 * <p>{@code verify()}가 서명(구글 공개키, 라이브러리가 캐시)·{@code iss}·{@code exp}·{@code aud}를 확인한다.
 * {@code aud}는 <b>웹 클라이언트 ID</b>다 — 앱이 {@code serverClientId}에 웹 클라이언트 ID를 넣고 토큰을
 * 받으므로 토큰의 aud도 웹 클라이언트 ID가 된다. 앱용 클라이언트 ID는 azp에 들어가며 따로 확인하지 않는다
 * (aud가 우리 웹 클라이언트 ID면 같은 구글 클라우드 프로젝트에 등록된 앱이 받은 토큰이다).
 *
 * <p>nonce는 1차 범위에서 확인하지 않는다(Google 가이드상 선택). refresh_token은 이 방식으로 받을 수 없어
 * {@code null} — 따라서 앱으로 가입한 계정은 탈퇴 시 구글 연동 해제(revoke)가 생략된다
 * ({@link GoogleUnlinkClient}는 저장된 토큰이 없으면 그냥 넘어간다).
 */
@Slf4j
@Component
public class GoogleIdTokenProfileVerifier {

    /** 웹 클라이언트 ID가 없으면 null — 서버는 정상 기동하고 앱 구글 로그인만 전부 실패시킨다 */
    private final GoogleIdTokenVerifier verifier;

    @Autowired
    public GoogleIdTokenProfileVerifier(OAuthProperties oAuthProperties) {
        this(buildVerifier(webClientIdOf(oAuthProperties)));
    }

    // 테스트용 — 실제 구글 공개키 없이 검증 결과를 주입한다
    GoogleIdTokenProfileVerifier(GoogleIdTokenVerifier verifier) {
        this.verifier = verifier;
    }

    private static String webClientIdOf(OAuthProperties oAuthProperties) {
        if (oAuthProperties.getProvider() == null) {
            return null;
        }
        OAuthProperties.Provider google = oAuthProperties.getProvider(OAuthProvider.GOOGLE);
        return google == null ? null : google.getClientId();
    }

    private static GoogleIdTokenVerifier buildVerifier(String webClientId) {
        if (webClientId == null || webClientId.isBlank()) {
            log.warn("oauth.provider.google.client-id(웹 클라이언트 ID)가 비어 있어 앱 구글 로그인은 모두 실패합니다");
            return null;
        }
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(List.of(webClientId))
                .build();
    }

    public OAuthUserProfile verify(String idToken) {
        if (verifier == null) {
            throw new OAuthInvalidIdTokenException();
        }
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (IllegalArgumentException e) {
            // JWT 형식 자체가 아님(파싱 실패)
            throw new OAuthInvalidIdTokenException();
        } catch (GeneralSecurityException | IOException e) {
            // 구글 공개키를 못 받아온 경우 등 — 토큰 탓이 아니라 서버 쪽 문제라 500으로 흘린다
            throw new IllegalStateException("구글 ID 토큰 검증 실패(공개키 조회 등)", e);
        }
        if (token == null) {
            throw new OAuthInvalidIdTokenException();
        }

        GoogleIdToken.Payload payload = token.getPayload();
        // 확인되지 않은 이메일은 쓰지 않는다 — null로 넘겨 추가정보 입력에서 이메일을 받게 한다(needEmail=true)
        String email = Boolean.TRUE.equals(payload.getEmailVerified()) ? payload.getEmail() : null;

        return new OAuthUserProfile(
                payload.getSubject(),
                email,
                (String) payload.get("name"),
                null
        );
    }
}
