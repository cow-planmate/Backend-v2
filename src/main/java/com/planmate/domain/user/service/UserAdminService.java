package com.planmate.domain.user.service;

import com.planmate.common.audit.entity.AdminActionLog;
import com.planmate.common.audit.enums.AdminActionType;
import com.planmate.common.audit.enums.AdminTargetType;
import com.planmate.common.audit.repository.AdminActionLogRepository;
import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 관리자 전용 계정 정지/해제. 사용자 자기 프로필 관리({@link UserService})와는 성격이 달라 별도 서비스로 둔다.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

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
}
