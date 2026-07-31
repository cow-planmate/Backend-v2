package com.planmate.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Gravatar 아바타 식별자(이메일 MD5) 계산.
 *
 * 프로필 이미지를 올리지 않은 사용자는 클라이언트가 Gravatar로 아이콘을 그리는데,
 * 타인의 아바타를 그리려면 이메일이 필요하다. 이메일 원문을 밖으로 내보내지 않기 위해
 * 서버가 해시만 계산해서 내려준다.
 *
 * 규칙(소문자 trim 후 MD5)은 프론트엔드 utils/gravatarUrl.jsx와 반드시 같아야 한다 —
 * 어긋나면 같은 사용자가 화면마다 다른 아이콘으로 보인다.
 */
public final class GravatarHash {

    private GravatarHash() {
    }

    /** 이메일이 없으면(소셜 로그인 일부) null — 이 경우 클라이언트는 닉네임 이니셜로 떨어진다. */
    public static String of(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }

        byte[] digest = md5(email.trim().toLowerCase(Locale.ROOT));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static byte[] md5(String value) {
        try {
            return MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // MD5는 모든 JVM이 제공하도록 규격에 명시돼 있어 실제로 발생하지 않는다
            throw new IllegalStateException("MD5를 사용할 수 없습니다.", e);
        }
    }
}
