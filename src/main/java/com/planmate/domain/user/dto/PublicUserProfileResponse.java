package com.planmate.domain.user.dto;

import com.planmate.domain.theme.dto.PreferredThemeDto;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * 타인에게 노출되는 프로필 (GET /api/user/profile/{userId}).
 *
 * 개인정보는 공개 프로필이라도 절대 담지 않는다 — 이메일·생년월일(나이)·성별·소셜 로그인 여부·
 * FCM 토큰은 응답 자체에 존재하지 않으므로 클라이언트가 감추는 것에 의존하지 않는다.
 * 일정도 개수만 노출하며, 내용은 플랜 권한 체계가 따로 판단한다.
 */
@Builder
public record PublicUserProfileResponse(
        UUID userId,
        String nickname,
        List<PreferredThemeDto> preferredThemes,
        int myPlanCount,
        int editablePlanCount
) {
}
