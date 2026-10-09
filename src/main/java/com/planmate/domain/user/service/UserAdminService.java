package com.planmate.domain.user.service;

import com.planmate.common.audit.entity.AdminActionLog;
import com.planmate.common.audit.enums.AdminActionType;
import com.planmate.common.audit.enums.AdminTargetType;
import com.planmate.common.audit.repository.AdminActionLogRepository;
import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.user.dto.AdminUserPageResponse;
import com.planmate.domain.user.dto.AdminUserSummaryResponse;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 관리자 전용 계정 정지/해제와 회원 검색. 사용자 자기 프로필 관리({@link UserService})와는 성격이 달라 별도 서비스로 둔다.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final int MAX_KEYWORD_LENGTH = 50;
    private static final int MAX_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final AdminActionLogRepository adminActionLogRepository;

    @Transactional
    public void suspend(UUID adminId, UUID targetUserId, LocalDateTime until, String reason) {
        if (adminId.equals(targetUserId)) {
            throw new PlanMateException(ErrorCode.INVALID_INPUT, "자기 자신은 정지할 수 없습니다.");
        }
        if (until != null && until.isBefore(LocalDateTime.now())) {
            throw new PlanMateException(ErrorCode.INVALID_INPUT, "정지 종료 시각은 현재 이후여야 합니다.");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(UserNotFoundException::new);
        // 관리자끼리 정지시키는 경로를 막는다. 권한을 잃은 관리자를 정지하려면 role 을 먼저 낮춰야 한다.
        if (user.getRole() == UserRole.ADMIN) {
            throw new PlanMateException(ErrorCode.INVALID_INPUT, "관리자 계정은 정지할 수 없습니다.");
        }
        user.suspend(until, reason);

        adminActionLogRepository.save(AdminActionLog.of(
                adminId, AdminActionType.SUSPEND_USER, AdminTargetType.USER, targetUserId.toString(), reason));
    }

    @Transactional
    public void unsuspend(UUID adminId, UUID targetUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(UserNotFoundException::new);
        user.unsuspend();

        adminActionLogRepository.save(AdminActionLog.of(
                adminId, AdminActionType.UNSUSPEND_USER, AdminTargetType.USER, targetUserId.toString(), null));
    }

    /** 닉네임·이메일로 회원을 찾는다. 탈퇴 계정은 제외된다. keyword 가 비면 전체를 본다. */
    @Transactional(readOnly = true)
    public AdminUserPageResponse search(String keyword, boolean suspendedOnly, int page, int size) {
        String normalized = keyword == null ? "" : keyword.strip();
        if (normalized.length() > MAX_KEYWORD_LENGTH) {
            throw new PlanMateException(ErrorCode.INVALID_INPUT, "검색어는 50자 이하로 입력해 주세요.");
        }
        Page<User> result = userRepository.searchForAdmin(normalized, suspendedOnly, LocalDateTime.now(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
        return new AdminUserPageResponse(
                result.map(AdminUserSummaryResponse::from).getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }
}
