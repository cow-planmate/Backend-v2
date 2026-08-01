package com.planmate.security;

import com.planmate.common.exception.domain.InvalidTokenException;
import com.planmate.common.exception.domain.TokenExpiredException;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.sharedsync.shared.auth.AuthenticationTokenResolver;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider implements AuthenticationTokenResolver {

    @Value("${jwt.access-token-expiry-seconds}")
    private long accessTokenExpirySeconds;

    @Value("${jwt.refresh-token-expiry-seconds}")
    private long refreshTokenExpirySeconds;

    private static final long EMAIL_VERIFICATION_TOKEN_EXPIRY_SECONDS = 600; // 10분

    private final JwtSigningKeys signingKeys;

    /**
     * 검증 시 헤더의 alg 를 보고 키를 고른다.
     *
     * <p>전환 기간에는 RS256(새로 발급한 것)과 HS256(배포 전에 발급된 것)이 섞여 돌아다닌다.
     * 특히 refresh 토큰은 7일짜리라 한동안 HS256 이 남는다. alg 를 무시하고 한 키로만
     * 검증하면 그 토큰들이 전부 무효가 되어 사용자가 로그아웃된다.
     *
     * <p>alg 를 토큰이 정하게 두는 것은 일반적으로 위험하지만(alg=none, RS256↔HS256 혼동
     * 공격), 여기서는 alg 값에 따라 <b>정해진 타입의 키만</b> 돌려주므로 공격자가 대칭키
     * 자리에 공개키를 밀어 넣는 경로가 없다. 그 외 alg 는 전부 거부한다.
     */
    private Key resolveVerificationKey(io.jsonwebtoken.Header header) {
        String alg = header.getAlgorithm();
        if (alg == null) {
            throw new InvalidTokenException();
        }
        if (alg.startsWith("RS")) {
            if (!signingKeys.isAsymmetric()) {
                throw new InvalidTokenException();
            }
            return signingKeys.getPublicKey();
        }
        // HS256 만 받으면 안 된다 — jjwt 는 signWith(key) 에서 키 길이에 맞는 알고리즘을
        // 자동으로 고른다. 지금 secret 은 472비트라 실제로 발급되는 토큰은 HS384 다.
        // HS256 만 통과시키면 기존 토큰이 전부 거부되어 사용자가 전부 로그아웃된다.
        if (alg.startsWith("HS")) {
            return signingKeys.getHmacKey();
        }
        // alg=none 을 비롯한 나머지는 전부 거부한다.
        throw new InvalidTokenException();
    }

    /** 발급은 항상 현재 가장 강한 방식으로 한다 — 키가 있으면 RS256, 없으면 HS256. */
    private Key signingKey() {
        return signingKeys.isAsymmetric() ? signingKeys.getPrivateKey() : signingKeys.getHmacKey();
    }

    /**
     * RS256 으로 서명할 때는 kid 를 헤더에 넣는다. 검증자(커뮤니티)가 JWKS 에서 어떤 키로
     * 확인해야 하는지 고르는 근거이며, 키를 교체할 때 두 키를 동시에 게시하고 넘어갈 수 있게 한다.
     */
    private io.jsonwebtoken.JwtBuilder withSigning(io.jsonwebtoken.JwtBuilder builder) {
        if (signingKeys.isAsymmetric()) {
            builder.header().keyId(signingKeys.getKeyId()).and();
        }
        return builder.signWith(signingKey());
    }

    public String createAccessToken(UUID userId, String role) {
        return createToken(userId, role, "access", accessTokenExpirySeconds);
    }

    public String createRefreshToken(UUID userId) {
        return createToken(userId, null, "refresh", refreshTokenExpirySeconds);
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public Duration getRefreshTokenExpiry() {
        return Duration.ofSeconds(refreshTokenExpirySeconds);
    }

    public String createEmailVerificationToken(String email, EmailVerificationPurpose purpose) {
        Instant now = Instant.now();
        return withSigning(Jwts.builder()
                .subject(email)
                .claim("purpose", purpose.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(EMAIL_VERIFICATION_TOKEN_EXPIRY_SECONDS))))
                .compact();
    }

    public String extractVerifiedEmail(String token, EmailVerificationPurpose expectedPurpose) {
        Claims claims = parseClaims(token);
        String purpose = claims.get("purpose", String.class);
        if (purpose == null || !purpose.equals(expectedPurpose.name())) {
            throw new InvalidTokenException();
        }
        return claims.getSubject();
    }

    public boolean isAccessToken(String token) {
        return "access".equals(parseClaims(token).get("typ", String.class));
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().keyLocator(this::resolveVerificationKey).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean validate(String token) {
        return validateToken(token);
    }

    @Override
    public String extractPrincipalId(String token) {
        return extractUserId(token).toString();
    }

    private String createToken(UUID userId, String role, String type, long expirySeconds) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("typ", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)));
        if (role != null) {
            builder.claim("role", role);
        }
        return withSigning(builder).compact();
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .keyLocator(this::resolveVerificationKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
    }
}
