package com.planmate.domain.user.internal;

import java.util.UUID;

/**
 * 내부 서비스에 노출하는 최소 사용자 정보.
 * profilePublic은 커뮤니티 서비스가 프로필 기반 목록(작성자별 게시글)을 막는 데 쓴다.
 *
 * profileImageUrl/avatarHash는 커뮤니티가 게시글·댓글 작성자 아이콘을 그리는 데 쓴다.
 * 아이콘 우선순위는 프로필 사진 → Gravatar(avatarHash) → 닉네임 이니셜이며,
 * 이메일 원문은 여기에 담지 않는다.
 *
 * deleted(탈퇴)인 사용자는 nickname/profileImageUrl/avatarHash가 모두 null이다 —
 * 탈퇴 후에도 개인정보를 계속 내려줄 이유가 없고, 수신측은 "탈퇴한 사용자"로 표시한다.
 */
public record InternalUserResponse(
        UUID userId,
        String nickname,
        boolean profilePublic,
        String profileImageUrl,
        String avatarHash,
        boolean deleted
) {

    /** 탈퇴 계정 — 식별자와 탈퇴 사실 외에는 아무것도 노출하지 않는다 */
    public static InternalUserResponse ofDeleted(UUID userId) {
        return new InternalUserResponse(userId, null, false, null, null, true);
    }
}
