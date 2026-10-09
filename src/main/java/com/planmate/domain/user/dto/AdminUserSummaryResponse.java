package com.planmate.domain.user.dto;

import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 관리자 회원 목록 한 줄. 정지 상태는 조회 시점 기준으로 계산한다.
 * 영구 정지는 suspendedUntil 이 sentinel 값이므로 {@code permanent} 로 따로 알려 준다.
 */
public record AdminUserSummaryResponse(
        UUID userId,
        String nickname,
        String email,
        UserRole role,
        LocalDateTime createdAt,
        boolean suspended,
        boolean permanent,
        LocalDateTime suspendedUntil,
        String suspendReason
) {

    public static AdminUserSummaryResponse from(User user) {
        boolean suspended = user.isSuspended();
        return new AdminUserSummaryResponse(
                user.getUserId(),
                user.getNickname(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt(),
                suspended,
                suspended && User.PERMANENT_SUSPENSION_UNTIL.equals(user.getSuspendedUntil()),
                suspended ? user.getSuspendedUntil() : null,
                suspended ? user.getSuspendReason() : null);
    }
}
