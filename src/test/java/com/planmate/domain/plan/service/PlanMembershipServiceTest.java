package com.planmate.domain.plan.service;

import com.planmate.common.exception.domain.PlanAccessDeniedException;
import com.planmate.common.exception.domain.PlanMemberNotFoundException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.domain.plan.dto.DeleteMultiplePlansResponse;
import com.planmate.domain.plan.dto.GetEditorsResponse;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlanMembershipServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID PLAN_ID_2 = UUID.randomUUID();

    @Mock
    private PlanRepository planRepository;
    @Mock
    private PlanMemberRepository planMemberRepository;

    @InjectMocks
    private PlanMembershipService planMembershipService;

    private User user() {
        return User.builder().userId(USER_ID).nickname("tester").build();
    }

    private Plan savedPlanStub() {
        return Plan.builder().planId(PLAN_ID).build();
    }

    // ---------- deleteMultiplePlans (#30) ----------

    @Test
    @DisplayName("플랜 다중 삭제 성공 - 소유한 플랜만 삭제되고 deletedPlanIds로 반환")
    void deleteMultiplePlans_success() {
        Plan ownedPlan = Plan.builder().planId(PLAN_ID).build();
        PlanMember ownedMembership = PlanMember.builder().plan(ownedPlan).user(user()).role(PlanMemberRole.OWNER).build();
        given(planMemberRepository.findByUserUserIdAndRoleAndPlanPlanIdIn(
                USER_ID, PlanMemberRole.OWNER, List.of(PLAN_ID, PLAN_ID_2)))
                .willReturn(List.of(ownedMembership));

        DeleteMultiplePlansResponse response = planMembershipService.deleteMultiplePlans(USER_ID, List.of(PLAN_ID, PLAN_ID_2));

        assertThat(response.deletedPlanIds()).containsExactly(PLAN_ID);
        assertThat(ownedPlan.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("플랜 다중 삭제 실패 - 소유한 플랜이 하나도 없으면 403")
    void deleteMultiplePlans_fail_noneOwned() {
        given(planMemberRepository.findByUserUserIdAndRoleAndPlanPlanIdIn(
                USER_ID, PlanMemberRole.OWNER, List.of(PLAN_ID)))
                .willReturn(List.of());

        assertThatThrownBy(() -> planMembershipService.deleteMultiplePlans(USER_ID, List.of(PLAN_ID)))
                .isInstanceOf(PlanAccessDeniedException.class);
    }

    // ---------- resignEditorAccess (#34) ----------

    @Test
    @DisplayName("본인 편집 권한 포기 성공")
    void resignEditorAccess_success() {
        PlanMember member = PlanMember.builder().plan(savedPlanStub()).user(user()).role(PlanMemberRole.EDITOR).build();
        given(planRepository.existsById(PLAN_ID)).willReturn(true);
        given(planMemberRepository.findByPlanPlanIdAndUserUserIdAndRole(PLAN_ID, USER_ID, PlanMemberRole.EDITOR))
                .willReturn(Optional.of(member));

        planMembershipService.resignEditorAccess(PLAN_ID, USER_ID);

        verify(planMemberRepository).delete(member);
    }

    @Test
    @DisplayName("본인 편집 권한 포기 실패 - 플랜 없음")
    void resignEditorAccess_fail_planNotFound() {
        given(planRepository.existsById(PLAN_ID)).willReturn(false);

        assertThatThrownBy(() -> planMembershipService.resignEditorAccess(PLAN_ID, USER_ID))
                .isInstanceOf(PlanNotFoundException.class);
        verify(planMemberRepository, never()).delete(any());
    }

    @Test
    @DisplayName("본인 편집 권한 포기 실패 - 편집자가 아님(소유자 포함)")
    void resignEditorAccess_fail_notEditor() {
        given(planRepository.existsById(PLAN_ID)).willReturn(true);
        given(planMemberRepository.findByPlanPlanIdAndUserUserIdAndRole(PLAN_ID, USER_ID, PlanMemberRole.EDITOR))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> planMembershipService.resignEditorAccess(PLAN_ID, USER_ID))
                .isInstanceOf(PlanMemberNotFoundException.class);
    }

    // ---------- removeEditorAccessByOwner (#35) ----------

    @Test
    @DisplayName("편집자 권한 박탈 성공")
    void removeEditorAccessByOwner_success() {
        UUID targetUserId = UUID.randomUUID();
        PlanMember member = PlanMember.builder().plan(savedPlanStub()).user(user()).role(PlanMemberRole.EDITOR).build();
        given(planMemberRepository.findByPlanPlanIdAndUserUserIdAndRole(PLAN_ID, targetUserId, PlanMemberRole.EDITOR))
                .willReturn(Optional.of(member));

        planMembershipService.removeEditorAccessByOwner(PLAN_ID, targetUserId);

        verify(planMemberRepository).delete(member);
    }

    @Test
    @DisplayName("편집자 권한 박탈 실패 - 대상이 편집자가 아님(소유자 자기 자신 포함)")
    void removeEditorAccessByOwner_fail_targetNotEditor() {
        UUID targetUserId = UUID.randomUUID();
        given(planMemberRepository.findByPlanPlanIdAndUserUserIdAndRole(PLAN_ID, targetUserId, PlanMemberRole.EDITOR))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> planMembershipService.removeEditorAccessByOwner(PLAN_ID, targetUserId))
                .isInstanceOf(PlanMemberNotFoundException.class);
    }

    // ---------- getEditors (#36) ----------

    @Test
    @DisplayName("편집자 목록 조회 성공 - EDITOR만 포함, OWNER 제외")
    void getEditors_success() {
        User editorUser = User.builder().userId(UUID.randomUUID()).nickname("editor-tester").build();
        PlanMember editorMember = PlanMember.builder().plan(savedPlanStub()).user(editorUser).role(PlanMemberRole.EDITOR).build();
        given(planMemberRepository.findByPlanPlanIdAndRole(PLAN_ID, PlanMemberRole.EDITOR))
                .willReturn(List.of(editorMember));

        GetEditorsResponse response = planMembershipService.getEditors(PLAN_ID);

        assertThat(response.editors()).hasSize(1);
        assertThat(response.editors().get(0).userId()).isEqualTo(editorUser.getUserId());
        assertThat(response.editors().get(0).nickname()).isEqualTo("editor-tester");
    }

    @Test
    @DisplayName("편집자 목록 조회 성공 - 편집자 없으면 빈 리스트")
    void getEditors_success_empty() {
        given(planMemberRepository.findByPlanPlanIdAndRole(PLAN_ID, PlanMemberRole.EDITOR))
                .willReturn(List.of());

        GetEditorsResponse response = planMembershipService.getEditors(PLAN_ID);

        assertThat(response.editors()).isEmpty();
    }
}
