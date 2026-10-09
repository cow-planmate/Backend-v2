package com.planmate.domain.user.service;

import com.planmate.common.audit.entity.AdminActionLog;
import com.planmate.common.audit.repository.AdminActionLogRepository;
import com.planmate.common.exception.PlanMateException;
import com.planmate.domain.user.dto.AdminUserPageResponse;
import com.planmate.domain.user.dto.AdminUserSummaryResponse;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 관리자 회원 검색과, 관리자 계정을 정지 대상에서 빼는 가드 */
@ExtendWith(MockitoExtension.class)
class UserAdminSearchServiceTest {

    @Mock UserRepository userRepository;
    @Mock AdminActionLogRepository adminActionLogRepository;
    @InjectMocks UserAdminService service;

    final UUID adminId = UUID.randomUUID();

    @Test
    void suspendingAnotherAdminIsRejectedAndNothingIsRecorded() {
        UUID targetId = UUID.randomUUID();
        User otherAdmin = user(UserRole.ADMIN);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(otherAdmin));

        assertThatThrownBy(() -> service.suspend(adminId, targetId, LocalDateTime.now().plusDays(1), "사유"))
                .isInstanceOf(PlanMateException.class)
                .hasMessageContaining("관리자 계정은 정지할 수 없습니다.");
        assertThat(otherAdmin.isSuspended()).isFalse();
        verify(adminActionLogRepository, never()).save(any(AdminActionLog.class));
    }

    @Test
    void searchTrimsTheKeywordAndShowsPermanentSuspensionAsPermanent() {
        User suspended = user(UserRole.USER);
        suspended.suspend(null, "영구 정지 사유");
        when(userRepository.searchForAdmin(eq("여행"), eq(true), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(suspended), PageRequest.of(0, 20), 1));

        AdminUserPageResponse response = service.search("  여행 ", true, 0, 20);

        assertThat(response.totalElements()).isEqualTo(1);
        AdminUserSummaryResponse item = response.items().get(0);
        assertThat(item.suspended()).isTrue();
        assertThat(item.permanent()).isTrue();
        assertThat(item.suspendReason()).isEqualTo("영구 정지 사유");
    }

    @Test
    void searchClampsPagingAndRejectsAnOverlongKeyword() {
        when(userRepository.searchForAdmin(eq(""), eq(false), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.search(null, false, -3, 500);

        verify(userRepository).searchForAdmin(eq(""), eq(false), any(LocalDateTime.class), eq(PageRequest.of(0, 50)));
        assertThatThrownBy(() -> service.search("가".repeat(51), false, 0, 20))
                .isInstanceOf(PlanMateException.class);
    }

    private static User user(UserRole role) {
        return User.builder()
                .provider(UserProvider.LOCAL)
                .email(UUID.randomUUID() + "@example.com")
                .nickname("회원" + UUID.randomUUID().toString().substring(0, 6))
                .role(role)
                .build();
    }
}
