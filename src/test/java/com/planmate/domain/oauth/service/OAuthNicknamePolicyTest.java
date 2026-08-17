package com.planmate.domain.oauth.service;

import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OAuthNicknamePolicyTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OAuthNicknamePolicy nicknamePolicy;

    @Test
    @DisplayName("sanitize() - 공백/특수문자를 제거하고 한글·영문·숫자만 남긴다")
    void sanitize_removesWhitespaceAndSpecialCharacters() {
        assertThat(nicknamePolicy.sanitize(" 홍 길동!! ")).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("sanitize() - 이모지(\\p{So})를 제거한다")
    void sanitize_removesEmoji() {
        assertThat(nicknamePolicy.sanitize("길동😀")).isEqualTo("길동");
    }

    @Test
    @DisplayName("sanitize() - 20자를 초과하면 앞 20자로 자른다")
    void sanitize_truncatesTo20Characters() {
        String longNickname = "a".repeat(25);

        assertThat(nicknamePolicy.sanitize(longNickname)).hasSize(20).isEqualTo("a".repeat(20));
    }

    @Test
    @DisplayName("sanitize() - null이면 \"user\"로 대체한다")
    void sanitize_nullFallsBackToUser() {
        assertThat(nicknamePolicy.sanitize(null)).isEqualTo("user");
    }

    @Test
    @DisplayName("sanitize() - 정제 후 빈 문자열이면(특수문자만 있던 경우) \"user\"로 대체한다")
    void sanitize_blankAfterCleaningFallsBackToUser() {
        assertThat(nicknamePolicy.sanitize("!!!😀😀😀")).isEqualTo("user");
    }

    @Test
    @DisplayName("resolveUnique() - 중복이 없으면 그대로 반환한다")
    void resolveUnique_noConflict_returnsAsIs() {
        given(userRepository.existsByNickname("홍길동")).willReturn(false);

        assertThat(nicknamePolicy.resolveUnique("홍길동")).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("resolveUnique() - 1회 충돌 시 뒤에 1을 붙인다")
    void resolveUnique_singleConflict_appendsOne() {
        given(userRepository.existsByNickname("홍길동")).willReturn(true);
        given(userRepository.existsByNickname("홍길동1")).willReturn(false);

        assertThat(nicknamePolicy.resolveUnique("홍길동")).isEqualTo("홍길동1");
    }

    @Test
    @DisplayName("resolveUnique() - 연속으로 충돌하면 충돌 안 나는 번호까지 계속 올린다")
    void resolveUnique_consecutiveConflicts_incrementsUntilAvailable() {
        given(userRepository.existsByNickname("user")).willReturn(true);
        given(userRepository.existsByNickname("user1")).willReturn(true);
        given(userRepository.existsByNickname("user2")).willReturn(true);
        given(userRepository.existsByNickname("user3")).willReturn(false);

        assertThat(nicknamePolicy.resolveUnique("user")).isEqualTo("user3");
    }
}
