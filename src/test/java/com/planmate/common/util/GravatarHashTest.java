package com.planmate.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GravatarHashTest {

    @Test
    @DisplayName("Gravatar 규격대로 이메일 MD5를 만든다")
    void hashesEmail() {
        // Gravatar 문서의 예시 값 — 프론트엔드 gravatarUrl.jsx(md5)와 같은 결과여야 한다
        assertThat(GravatarHash.of("MyEmailAddress@example.com "))
                .isEqualTo("0bc83cb571cd1c50ba6f3e8a78ef1346");
    }

    @Test
    @DisplayName("대소문자·앞뒤 공백이 달라도 같은 해시가 나온다")
    void normalizesEmail() {
        assertThat(GravatarHash.of("  User@Planmate.com"))
                .isEqualTo(GravatarHash.of("user@planmate.com"));
    }

    @Test
    @DisplayName("이메일이 없으면 null — 클라이언트가 닉네임 이니셜로 떨어진다")
    void returnsNullWithoutEmail() {
        assertThat(GravatarHash.of(null)).isNull();
        assertThat(GravatarHash.of("   ")).isNull();
    }
}
