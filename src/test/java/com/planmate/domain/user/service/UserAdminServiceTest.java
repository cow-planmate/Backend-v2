package com.planmate.domain.user.service;

import com.planmate.common.audit.entity.AdminActionLog;
import com.planmate.common.audit.enums.AdminActionType;
import com.planmate.common.audit.enums.AdminTargetType;
import com.planmate.common.audit.repository.AdminActionLogRepository;
import com.planmate.common.exception.PlanMateException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminActionLogRepository adminActionLogRepository;

    @InjectMocks
    private UserAdminService userAdminService;

    private UUID adminId;
    private UUID targetUserId;
    private User targetUser;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();
        targetUser = User.builder()
                .userId(targetUserId)
                .provider(UserProvider.LOCAL)
                .email("target@example.com")
                .nickname("target")
                .role(UserRole.USER)
                .build();
    }

    @Test
    @DisplayName("정지 성공 - suspendedUntil/suspendReason이 채워지고 감사로그가 남는다")
    void suspend_success() {
        given(userRepository.findById(targetUserId)).willReturn(Optional.of(targetUser));
        LocalDateTime until = LocalDateTime.now().plusDays(7);

        userAdminService.suspend(adminId, targetUserId, until, "도배");

        assertThat(targetUser.getSuspendedUntil()).isEqualTo(until);
        assertThat(targetUser.getSuspendReason()).isEqualTo("도배");
        assertThat(targetUser.isSuspended()).isTrue();

        ArgumentCaptor<AdminActionLog> captor = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(captor.capture());
        AdminActionLog log = captor.getValue();
        assertThat(log.getAdminUserId()).isEqualTo(adminId);
        assertThat(log.getActionType()).isEqualTo(AdminActionType.SUSPEND_USER);
        assertThat(log.getTargetType()).isEqualTo(AdminTargetType.USER);
        assertThat(log.getTargetId()).isEqualTo(targetUserId.toString());
        assertThat(log.getReason()).isEqualTo("도배");
    }

    @Test
    @DisplayName("정지 성공 - until을 null로 주면 영구 정지 sentinel로 저장된다")
    void suspend_success_permanent() {
        given(userRepository.findById(targetUserId)).willReturn(Optional.of(targetUser));

        userAdminService.suspend(adminId, targetUserId, null, "심각한 위반");

        assertThat(targetUser.getSuspendedUntil()).isEqualTo(User.PERMANENT_SUSPENSION_UNTIL);
        assertThat(targetUser.isSuspended()).isTrue();
    }

    @Test
    @DisplayName("정지 실패 - 자기 자신은 정지할 수 없다")
    void suspend_fail_self() {
        assertThatThrownBy(() -> userAdminService.suspend(adminId, adminId, null, "사유"))
                .isInstanceOf(PlanMateException.class);

        verify(userRepository, never()).findById(any());
        verify(adminActionLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("정지 실패 - until이 과거면 조용히 무효화되지 않고 예외로 막는다")
    void suspend_fail_pastUntil() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);

        assertThatThrownBy(() -> userAdminService.suspend(adminId, targetUserId, past, "사유"))
                .isInstanceOf(PlanMateException.class);

        verify(userRepository, never()).findById(any());
        verify(adminActionLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("정지 실패 - 대상 유저가 없으면 UserNotFoundException")
    void suspend_fail_userNotFound() {
        given(userRepository.findById(targetUserId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userAdminService.suspend(adminId, targetUserId, null, "사유"))
                .isInstanceOf(UserNotFoundException.class);

        verify(adminActionLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("정지 해제 성공 - suspendedUntil/suspendReason이 비워지고 감사로그가 남는다")
    void unsuspend_success() {
        targetUser.suspend(LocalDateTime.now().plusDays(1), "이전 사유");
        given(userRepository.findById(targetUserId)).willReturn(Optional.of(targetUser));

        userAdminService.unsuspend(adminId, targetUserId);

        assertThat(targetUser.getSuspendedUntil()).isNull();
        assertThat(targetUser.getSuspendReason()).isNull();
        assertThat(targetUser.isSuspended()).isFalse();

        ArgumentCaptor<AdminActionLog> captor = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActionType()).isEqualTo(AdminActionType.UNSUSPEND_USER);
        assertThat(captor.getValue().getReason()).isNull();
    }

    @Test
    @DisplayName("정지 해제 실패 - 대상 유저가 없으면 UserNotFoundException")
    void unsuspend_fail_userNotFound() {
        given(userRepository.findById(targetUserId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userAdminService.unsuspend(adminId, targetUserId))
                .isInstanceOf(UserNotFoundException.class);

        verify(adminActionLogRepository, never()).save(any());
    }
}
