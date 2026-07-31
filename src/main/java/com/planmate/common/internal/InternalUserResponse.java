package com.planmate.common.internal;

import java.util.UUID;

/**
 * 내부 서비스에 노출하는 최소 사용자 정보.
 * profilePublic은 커뮤니티 서비스가 프로필 기반 목록(작성자별 게시글)을 막는 데 쓴다.
 *
 * profileImageUrl/avatarHash는 커뮤니티가 게시글·댓글 작성자 아이콘을 그리는 데 쓴다.
 * 아이콘 우선순위는 프로필 사진 → Gravatar(avatarHash) → 닉네임 이니셜이며,
 * 이메일 원문은 여기에 담지 않는다.
 */
public record InternalUserResponse(
        UUID userId,
        String nickname,
        boolean profilePublic,
        String profileImageUrl,
        String avatarHash
) {
}
