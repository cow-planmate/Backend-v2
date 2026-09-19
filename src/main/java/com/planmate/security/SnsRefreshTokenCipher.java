package com.planmate.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * SNS(OAuth) refresh_token을 AES-256-GCM으로 암/복호화한다. provider마다 별도 컴포넌트를
 * 두지 않는 이유는 {@code User.snsRefreshToken}이 provider 중립 컬럼 하나이기 때문이다
 * (04_기능/OAuth/C_SNS_연동해제.md 0-4절/4-1절).
 *
 * <p>{@link JwtSigningKeys}와 동일한 원칙 — 키가 없으면 기동하지 않는다.
 */
@Component
public class SnsRefreshTokenCipher {

    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    /** Base64로 인코딩된 32바이트(AES-256) 키. 필수 — 없으면 기동하지 않는다. */
    @Value("${security.sns-refresh-token-key:}")
    private String base64Key;

    private SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    void init() {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "SNS_REFRESH_TOKEN_ENC_KEY 가 설정되지 않았다. "
                            + "SNS refresh_token 암호화가 불가능하므로 기동하지 않는다. "
                            + "생성: openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("security.sns-refresh-token-key 가 올바른 Base64 값이 아니다", e);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "security.sns-refresh-token-key 는 32바이트(AES-256)여야 한다. 실제 길이=" + decoded.length);
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** 평문을 암호화해 {@code Base64(IV(12바이트) + ciphertext+tag)} 형태의 문자열로 반환한다. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("SNS refresh_token 암호화 실패", e);
        }
    }

    /** {@link #encrypt(String)}가 만든 문자열을 원문으로 복원한다. */
    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("SNS refresh_token 복호화 실패", e);
        }
    }
}
