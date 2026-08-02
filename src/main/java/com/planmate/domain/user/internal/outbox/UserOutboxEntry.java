package com.planmate.domain.user.internal.outbox;

import java.util.UUID;

/**
 * outbox 한 행. 변경 시점의 값 전체를 담는다(델타가 아니다).
 *
 * 전체 스냅샷이라서 재생 순서가 뒤바뀌어도, 중간 행이 보존 기간에 걸려 지워져도
 * 최종 상태가 같다. 소비자는 seq 를 보고 자기가 이미 반영한 것보다 낮은 갱신만 버리면 된다.
 *
 * 탈퇴 계정은 nickname/profileImageUrl/avatarHash 가 모두 null 이다.
 */
public record UserOutboxEntry(
        long seq,
        UUID userId,
        String nickname,
        String profileImageUrl,
        String avatarHash,
        boolean profilePublic,
        boolean deleted
) {
}
