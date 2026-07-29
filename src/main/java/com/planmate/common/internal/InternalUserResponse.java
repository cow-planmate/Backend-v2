package com.planmate.common.internal;

import java.util.UUID;

/**
 * 내부 서비스에 노출하는 최소 사용자 정보.
 * profilePublic은 커뮤니티 서비스가 프로필 기반 목록(작성자별 게시글)을 막는 데 쓴다.
 */
public record InternalUserResponse(UUID userId, String nickname, boolean profilePublic) {
}
