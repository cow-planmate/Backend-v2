package com.planmate.security;

import io.jsonwebtoken.security.Jwks;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 토큰 서명 공개키를 JWKS 로 게시한다. 검증자(커뮤니티)가 여기서 공개키를 받아
 * RS256 서명을 확인한다.
 *
 * <p>공개키이므로 인증을 걸지 않는다 — 애초에 공개하려고 만든 값이다. 다만 실제 트래픽은
 * 컨테이너 간 내부 호출이라 nginx 로 외부에 노출할 필요가 없다.
 *
 * <p><b>여기에 비공개 키가 섞이면 전체 인증이 무너진다.</b> 그래서 {@link JwtSigningKeys}
 * 의 공개키만 받아 JWK 를 만든다 — 비공개 키를 넘기면 jjwt 가 d/p/q 까지 직렬화한다.
 * 아래 build 인자를 절대 getPrivateKey() 로 바꾸지 말 것.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtSigningKeys signingKeys;

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        List<Map<String, Object>> keys = signingKeys.isAsymmetric()
                ? List.of(Jwks.builder()
                        .key(signingKeys.getPublicKey())   // 반드시 공개키
                        .idFromThumbprint()
                        .build())
                // 아직 RS256 으로 전환하기 전 — 빈 목록을 준다. 404 로 두면 검증자가
                // "서버 장애"와 "아직 전환 전"을 구분하지 못한다.
                : List.of();

        return ResponseEntity.ok()
                // 키는 거의 안 바뀐다. 검증자가 매 요청 받아가지 않도록 캐시를 허용하되,
                // 키 교체가 하루 안에 퍼지도록 짧게 잡는다.
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                .body(Map.of("keys", keys));
    }
}
