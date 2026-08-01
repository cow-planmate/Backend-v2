package com.planmate.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비대칭 전환의 안전장치를 고정한다. 여기서 깨지면 전 사용자가 로그아웃되거나
 * (더 나쁘게는) 개인키가 공개된다.
 */
class JwtSigningKeysTest {

    private static final String SECRET = "dkdlstodwoaldlTsmsrjaksgkaustjtkfrhtlvekdurltjejsmffudigkseo";

    private static String generatePkcs8Pem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static JwtSigningKeys keys(String pem) {
        JwtSigningKeys signingKeys = new JwtSigningKeys();
        ReflectionTestUtils.setField(signingKeys, "secret", SECRET);
        ReflectionTestUtils.setField(signingKeys, "privateKeyPem", pem);
        ReflectionTestUtils.invokeMethod(signingKeys, "init");
        return signingKeys;
    }

    @Test
    @DisplayName("개인키가 없으면 대칭키 발급 상태로 남는다(코드 배포와 키 투입을 분리하기 위함)")
    void fallsBackToSymmetricWithoutKey() {
        JwtSigningKeys signingKeys = keys("");

        assertThat(signingKeys.isAsymmetric()).isFalse();
        assertThat(signingKeys.getHmacKey()).isNotNull();
        assertThat(signingKeys.getPublicKey()).isNull();
    }

    @Test
    @DisplayName("PEM 을 주면 공개키를 개인키에서 유도하고 kid 를 만든다")
    void derivesPublicKeyAndKeyId() throws Exception {
        JwtSigningKeys signingKeys = keys(generatePkcs8Pem());

        assertThat(signingKeys.isAsymmetric()).isTrue();
        assertThat(signingKeys.getPublicKey().getModulus())
                .isEqualTo(((RSAPrivateKey) signingKeys.getPrivateKey()).getModulus());
        assertThat(signingKeys.getKeyId()).isNotBlank();
    }

    @Test
    @DisplayName("PEM 이 줄바꿈 없이 접혀 들어와도 읽는다(.env 한 줄 주입)")
    void readsFoldedPem() throws Exception {
        String folded = generatePkcs8Pem().replace("\n", "\\n");

        assertThat(keys(folded.replace("\\n", "\n")).isAsymmetric()).isTrue();
        // 공백을 모두 제거하는 파서라 실제 개행이 없어도 동작한다
        assertThat(keys(generatePkcs8Pem().replaceAll("\\s+", "")).isAsymmetric()).isTrue();
    }

    @Test
    @DisplayName("JWKS 에는 공개키만 실린다 — 개인키 성분이 새면 인증이 통째로 무너진다")
    void jwksNeverExposesPrivateKey() throws Exception {
        JwtSigningKeys signingKeys = keys(generatePkcs8Pem());

        // toString 이 아니라 실제로 응답에 실리는 JSON 을 봐야 의미가 있다
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new JwksController(signingKeys).jwks().getBody());

        assertThat(json).contains("\"kty\"").contains("RSA");
        // RSA 개인키 성분: d(비밀지수), p·q(소인수), dp·dq·qi(CRT)
        assertThat(json).doesNotContain("\"d\"")
                .doesNotContain("\"p\"").doesNotContain("\"q\"")
                .doesNotContain("\"dp\"").doesNotContain("\"dq\"").doesNotContain("\"qi\"");
    }

    @Test
    @DisplayName("전환 전에는 JWKS 가 404 가 아니라 빈 키 목록을 준다")
    void jwksReturnsEmptyListBeforeMigration() {
        Object body = new JwksController(keys("")).jwks().getBody();

        assertThat(body).isInstanceOf(Map.class);
        assertThat((java.util.List<?>) ((Map<?, ?>) body).get("keys")).isEmpty();
    }

    @Test
    @DisplayName("RS256 발급 후에도 기존 HS 토큰을 검증한다 — 이게 깨지면 전 사용자가 로그아웃된다")
    void stillVerifiesLegacySymmetricTokens() throws Exception {
        JwtSigningKeys signingKeys = keys(generatePkcs8Pem());
        JwtTokenProvider provider = new JwtTokenProvider(signingKeys);
        ReflectionTestUtils.setField(provider, "accessTokenExpirySeconds", 900L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirySeconds", 604800L);

        // 전환 전에 발급된 토큰. jjwt 가 키 길이에 맞춰 HS384 를 고른다 — HS256 이 아니다.
        String legacy = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("typ", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKeys.getHmacKey())
                .compact();

        assertThat(provider.validateToken(legacy)).isTrue();

        // 새로 발급하는 토큰은 RS256 이고 kid 를 달고 있다
        String issued = provider.createAccessToken(UUID.randomUUID(), "ROLE_USER");
        assertThat(provider.validateToken(issued)).isTrue();
        assertThat(new String(Base64.getUrlDecoder().decode(issued.split("\\.")[0])))
                .contains("RS256")
                .contains(signingKeys.getKeyId());
    }

    @Test
    @DisplayName("alg=none 토큰은 거부한다")
    void rejectsUnsignedToken() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider(keys(generatePkcs8Pem()));

        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"x\"}".getBytes());

        assertThat(provider.validateToken(header + "." + payload + ".")).isFalse();
    }
}
