package com.planmate.security;

import io.jsonwebtoken.security.Jwks;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * 토큰 서명에 쓰는 RS256 키를 한곳에서 들고 있다.
 *
 * <p>이 서비스가 신원의 원천(발급자)이고 나머지는 검증자다. 예전에는 모두가 같은 HS256
 * 대칭키를 나눠 가졌는데, 그러면 <b>검증만 하면 되는 쪽이 이 서비스의 토큰을 위조할 수
 * 있다</b>. RS256 은 서명을 여기로 가두고, 다른 서비스는 JWKS 로 공개키만 받는다.
 *
 * <p><b>대칭키 경로는 2026-08-22 에 걷어냈다.</b> 전환기에는 배포 전에 발급된 HS256 토큰을
 * 위해 검증 경로를 남겨 두었는데, 그 유예는 가장 긴 refresh 토큰(7일)이면 끝난다.
 * 실제 전환은 2026-08-02 였고 그 뒤로도 {@code JWT_SECRET} 이 계속 주입되고 있었다 —
 * 그 값을 아는 누구나 임의의 사용자로 토큰을 만들 수 있었으므로, RS256 으로 옮긴 의미가
 * 없는 상태였다. 되살리지 말 것.
 *
 * <p>키가 없으면 <b>기동하지 않는다.</b> 예전에는 HS256 발급으로 조용히 떨어졌는데(코드 배포와
 * 키 투입을 분리하려던 장치), 전환이 끝난 지금 그 상태는 유예가 아니라 사고다.
 */
@Slf4j
@Getter
@Component
public class JwtSigningKeys {

    /** RS256 서명용 PKCS#8 PEM. 필수 — 없으면 기동하지 않는다. */
    @Value("${jwt.private-key:}")
    private String privateKeyPem;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String keyId;

    @PostConstruct
    void init() {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            // 예전에는 여기서 HS256 발급으로 떨어졌다. 그러면 JWKS 가 빈 목록을 주고 검증자들이
            // 전부 토큰을 거부하는데, 원인은 경고 로그 한 줄뿐이다. 차라리 안 뜨는 편이 낫다.
            throw new IllegalStateException(
                    "JWT_PRIVATE_KEY 가 설정되지 않았다. 토큰 서명이 불가능하므로 기동하지 않는다. "
                            + "생성: deploy/gen-jwt-key.sh");
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
            // 키를 넣었는데 형식이 안 맞는 상태는 반드시 드러나야 한다. 삼키면 서명 키가
            // 없는 채로 떠서 첫 로그인에서야 터진다.
            throw new IllegalStateException("jwt.private-key 를 읽지 못했다 — PKCS#8 PEM 이어야 한다", e);
        }
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
