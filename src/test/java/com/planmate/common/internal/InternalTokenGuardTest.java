package com.planmate.common.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공유 토큰은 지금처럼 엄격하게(없으면 기동 실패), AI 토큰은 선택 설정으로(없으면 경고만) —
 * AI 토큰 시크릿보다 이미지가 먼저 나가도 v2 가 멈추지 않게 하는 것이 목적이다.
 */
class InternalTokenGuardTest {

    private static final String STRONG_SHARED = "shared-0123456789abcdef0123456789abcdef";
    private static final String STRONG_AI = "ai-0123456789abcdef0123456789abcdef012";

    private InternalTokenGuard guard(String shared, String ai) {
        InternalTokenGuard guard = new InternalTokenGuard();
        ReflectionTestUtils.setField(guard, "internalApiToken", shared);
        ReflectionTestUtils.setField(guard, "aiToken", ai);
        return guard;
    }

    // ---------- 공유 토큰 — 기존 규칙 그대로 ----------

    @Test
    @DisplayName("공유 토큰이 비면 기동 실패")
    void sharedBlank_fails() {
        assertThatThrownBy(() -> guard("", STRONG_AI).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    @Test
    @DisplayName("공유 토큰이 공개된 값이거나 짧으면 기동 실패")
    void sharedWeak_fails() {
        assertThatThrownBy(() -> guard("local-internal-token", "").verify())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard("short-token", "").verify())
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- AI 토큰 — 선택 설정 ----------

    @Test
    @DisplayName("AI 토큰이 비어 있거나 없으면 기동한다(경고만)")
    void aiBlankOrNull_starts() {
        assertThatCode(() -> guard(STRONG_SHARED, "").verify()).doesNotThrowAnyException();
        assertThatCode(() -> guard(STRONG_SHARED, null).verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AI 토큰이 충분히 강하면 기동한다")
    void aiStrong_starts() {
        assertThatCode(() -> guard(STRONG_SHARED, STRONG_AI).verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AI 토큰이 있는데 31자이거나 공개된 값이면 기동 실패 — 잘못 넣은 값은 바로 드러나게")
    void aiWeak_fails() {
        assertThatThrownBy(() -> guard(STRONG_SHARED, "a".repeat(31)).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_AI_TOKEN");
        assertThatThrownBy(() -> guard(STRONG_SHARED, "changeme").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_AI_TOKEN");
    }

    @Test
    @DisplayName("AI 토큰이 공유 토큰과 같으면 기동 실패 — 권한을 좁히는 의미가 없어진다")
    void aiSameAsShared_fails() {
        assertThatThrownBy(() -> guard(STRONG_SHARED, STRONG_SHARED).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_AI_TOKEN");
    }
}
