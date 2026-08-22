package com.planmate.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 비대칭 전환의 안전장치를 고정한다. 여기서 깨지면 전 사용자가 로그아웃되거나
 * (더 나쁘게는) 개인키가 공개된다.
 */
class JwtSigningKeysTest {

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
        ReflectionTestUtils.setField(signingKeys, "privateKeyPem", pem);
        ReflectionTestUtils.invokeMethod(signingKeys, "init");
        return signingKeys;
    }

    @Test
    @DisplayName("개인키가 없으면 기동하지 않는다 — 예전처럼 대칭키로 조용히 떨어지지 않는다")
    void refusesToStartWithoutKey() {
        // 대칭키 fallback 이 있던 시절에는 키 투입을 잊어도 서버가 떴고, JWKS 가 빈 목록을
        // 주면서 검증자들이 전부 401 을 냈다. 원인은 경고 로그 한 줄뿐이었다.
        assertThatThrownBy(() -> keys(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    @DisplayName("PEM 을 주면 공개키를 개인키에서 유도하고 kid 를 만든다")
    void derivesPublicKeyAndKeyId() throws Exception {
        JwtSigningKeys signingKeys = keys(generatePkcs8Pem());

        assertThat(signingKeys.getPublicKey().getModulus())
                .isEqualTo(((RSAPrivateKey) signingKeys.getPrivateKey()).getModulus());
        assertThat(signingKeys.getKeyId()).isNotBlank();
    }

    @Test
    @DisplayName("PEM 이 줄바꿈 없이 접혀 들어와도 읽는다(.env 한 줄 주입)")
    void readsFoldedPem() throws Exception {
        String folded = generatePkcs8Pem().replace("\n", "\\n");

        assertThat(keys(folded.replace("\\n", "\n")).getPublicKey()).isNotNull();
        // 공백을 모두 제거하는 파서라 실제 개행이 없어도 동작한다
        assertThat(keys(generatePkcs8Pem().replaceAll("\\s+", "")).getPublicKey()).isNotNull();
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
    @DisplayName("JWKS 는 항상 키 하나를 게시한다 — 키 없이는 서버가 뜨지 않으므로 빈 목록이 없다")
    void jwksAlwaysPublishesTheSigningKey() throws Exception {
        Object body = new JwksController(keys(generatePkcs8Pem())).jwks().getBody();

        assertThat(body).isInstanceOf(Map.class);
        assertThat((java.util.List<?>) ((Map<?, ?>) body).get("keys")).hasSize(1);
    }

    @Test
    @DisplayName("대칭키로 서명한 토큰은 거부한다 — 전환기 HS 경로는 걷어냈다")
    void rejectsSymmetricallySignedTokens() throws Exception {
        JwtSigningKeys signingKeys = keys(generatePkcs8Pem());
        JwtTokenProvider provider = new JwtTokenProvider(signingKeys);
        ReflectionTestUtils.setField(provider, "accessTokenExpirySeconds", 900L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirySeconds", 604800L);

        // 유예 기간이 끝난 뒤에도 JWT_SECRET 이 주입되고 있었다. 그 값을 아는 누구나
        // 이런 토큰을 만들어 임의의 사용자로 통과할 수 있었다.
        String forged = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("typ", "access")
                .claim("role", "ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(
                        "dkdlstodwoaldlTsmsrjaksgkaustjtkfrhtlvekdurltjejsmffudigkseo".getBytes(UTF_8)))
                .compact();

        assertThat(provider.validateToken(forged)).isFalse();

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
