package com.planmate.security;

import io.jsonwebtoken.security.Jwks;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import io.jsonwebtoken.security.Keys;

/**
 * 토큰 서명에 쓰는 키를 한곳에서 들고 있는다.
 *
 * <p>이 서비스가 신원의 원천(발급자)이고 커뮤니티는 검증자다. 지금까지는 둘이 같은 HS256
 * 대칭키를 나눠 가졌는데, 그러면 <b>커뮤니티가 이 서비스의 토큰을 위조할 수 있다</b> —
 * 서비스가 분리됐다고 말하기 어렵다. RS256 으로 바꾸면 서명은 여기서만 가능하고 커뮤니티는
 * 공개키로 검증만 한다.
 *
 * <p><b>전환 중에는 두 방식을 모두 검증한다.</b> 배포 시점에 이미 발급된 HS256 토큰이
 * 살아 있기 때문이다. access 토큰은 15분이면 소진되지만 refresh 토큰은 7일짜리라,
 * HS256 검증 경로는 최소 그만큼 남겨야 한다. 발급은 RS256 으로만 한다.
 *
 * <p>비공개 키가 없으면 HS256 발급으로 떨어진다. 코드를 먼저 배포하고 키를 나중에 넣는
 * 순서를 가능하게 하려는 것이다. 조용히 내려가면 위험하므로 기동 로그에 크게 남긴다.
 */
@Slf4j
@Getter
@Component
public class JwtSigningKeys {

    /** PKCS#8 PEM. 비어 있으면 HS256 발급으로 떨어진다(전환 전 상태). */
    @Value("${jwt.private-key:}")
    private String privateKeyPem;

    /** 전환 기간 동안 예전 토큰을 검증하기 위해 남겨둔다. */
    @Value("${jwt.secret}")
    private String secret;

    private SecretKey hmacKey;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String keyId;

    @PostConstruct
    void init() {
        this.hmacKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            log.warn("jwt.private-key 가 없어 토큰을 HS256(대칭키)으로 발급한다. "
                    + "커뮤니티와 서명키를 공유하는 상태이며, JWKS 는 빈 키 목록을 반환한다.");
            return;
        }

        try {
            this.privateKey = readPrivateKey(privateKeyPem);
            // 공개키는 설정으로 받지 않고 비공개 키에서 유도한다 — 둘을 따로 두면
            // 어긋났을 때 "서명은 되는데 아무도 검증 못 하는" 상태가 조용히 만들어진다.
            this.publicKey = derivePublicKey(this.privateKey);
            // kid 는 공개키의 RFC 7638 지문이다. 설정할 값이 없고, 키를 바꾸면 자동으로 바뀐다.
            this.keyId = Jwks.builder().key(this.publicKey).idFromThumbprint().build().getId();
            log.info("토큰을 RS256 으로 발급한다 (kid={})", this.keyId);
        } catch (Exception e) {
            // 여기서 삼키면 대칭키로 조용히 되돌아간다. 키를 넣었는데 안 먹는 상태는
            // 반드시 드러나야 하므로 기동을 실패시킨다.
            throw new IllegalStateException("jwt.private-key 를 읽지 못했다 — PKCS#8 PEM 이어야 한다", e);
        }
    }

    /** RS256 으로 서명할 수 있는 상태인지. */
    public boolean isAsymmetric() {
        return privateKey != null;
    }

    private static RSAPrivateKey readPrivateKey(String pem) throws Exception {
        // PEM 은 여러 줄인데 .env 는 한 줄이라, 보통 \n 으로 접어서 넣는다. docker compose 는
        // 그 두 글자를 실제 줄바꿈으로 풀어주지 않으므로 여기서 푼다 — 안 풀면 배포 시점에
        // "PKCS#8 이어야 한다"로 기동이 죽는다.
        // 헤더/푸터는 BEGIN PRIVATE KEY / BEGIN RSA PRIVATE KEY 어느 쪽이든, 공백이 이미
        // 지워진 형태여도 걸리도록 통째로 지운다.
        String base64 = pem
                .replace("\\n", "\n")
                .replaceAll("-----[^-]*-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAPublicKey derivePublicKey(RSAPrivateKey key) throws Exception {
        if (!(key instanceof RSAPrivateCrtKey crt)) {
            // PKCS#8 이면 보통 CRT 파라미터가 들어 있다. 없으면 공개 지수를 알 수 없어
            // 공개키를 만들 수 없으므로, 공개키를 따로 받는 대신 명확히 실패시킨다.
            throw new IllegalStateException(
                    "비공개 키에 CRT 파라미터가 없어 공개키를 유도할 수 없다 — openssl 로 생성한 PKCS#8 키를 쓸 것");
        }
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }
}
