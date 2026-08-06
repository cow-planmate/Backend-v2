package com.planmate.domain.checklist.service;

import com.planmate.common.exception.domain.ChecklistItemAccessDeniedException;
import com.planmate.common.exception.domain.ChecklistItemNotFoundException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.checklist.dto.ChecklistItemDto;
import com.planmate.domain.checklist.dto.CreateChecklistItemResponse;
import com.planmate.domain.checklist.dto.GetChecklistResponse;
import com.planmate.domain.checklist.entity.PlanPersonalChecklistItem;
import com.planmate.domain.checklist.repository.PlanPersonalChecklistItemRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlanPersonalChecklistServiceTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final Long ITEM_ID = 1L;

    @Mock
    private PlanPersonalChecklistItemRepository personalChecklistItemRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PlanPersonalChecklistService personalChecklistService;

    private User user(UUID userId) {
        return User.builder().userId(userId).nickname("tester").build();
    }

    private Plan plan() {
        return Plan.builder().planId(PLAN_ID).build();
    }

    private PlanPersonalChecklistItem item(UUID ownerId) {
        return PlanPersonalChecklistItem.builder()
                .personalChecklistItemId(ITEM_ID)
                .plan(plan())
                .user(user(ownerId))
                .content("여권")
                .isChecked(false)
                .sortOrder(0)
                .build();
    }

    @Test
    @DisplayName("개인 체크리스트 조회 성공 - sortOrder 순으로 매핑된다")
    void getChecklist_success() {
        PlanPersonalChecklistItem item = item(USER_ID);
        given(personalChecklistItemRepository.findByPlanPlanIdAndUserUserIdOrderBySortOrder(PLAN_ID, USER_ID))
                .willReturn(List.of(item));

        GetChecklistResponse response = personalChecklistService.getChecklist(PLAN_ID, USER_ID);

        assertThat(response.items()).hasSize(1);
        ChecklistItemDto dto = response.items().get(0);
        assertThat(dto.itemId()).isEqualTo(ITEM_ID);
        assertThat(dto.content()).isEqualTo("여권");
        assertThat(dto.isChecked()).isFalse();
    }

    @Test
    @DisplayName("개인 체크리스트 항목 생성 성공 - sortOrder는 기존 개수만큼 뒤에 붙는다")
    void createItem_success() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(personalChecklistItemRepository.countByPlanPlanIdAndUserUserId(PLAN_ID, USER_ID)).willReturn(2L);
        given(personalChecklistItemRepository.save(org.mockito.ArgumentMatchers.any(PlanPersonalChecklistItem.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ArgumentCaptor<PlanPersonalChecklistItem> captor = ArgumentCaptor.forClass(PlanPersonalChecklistItem.class);

        CreateChecklistItemResponse response = personalChecklistService.createItem(PLAN_ID, USER_ID, "우산");

        verify(personalChecklistItemRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("우산");
        assertThat(captor.getValue().getSortOrder()).isEqualTo(2);
        assertThat(captor.getValue().isChecked()).isFalse();
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("개인 체크리스트 항목 생성 실패 - 플랜 없음")
    void createItem_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> personalChecklistService.createItem(PLAN_ID, USER_ID, "우산"))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    @DisplayName("개인 체크리스트 항목 생성 실패 - 사용자 없음")
    void createItem_fail_userNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> personalChecklistService.createItem(PLAN_ID, USER_ID, "우산"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("개인 체크리스트 항목 내용 수정 성공")
    void editContent_success() {
        PlanPersonalChecklistItem item = item(USER_ID);
        given(personalChecklistItemRepository.findByPersonalChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(item));

        personalChecklistService.editContent(PLAN_ID, USER_ID, ITEM_ID, "카메라 충전기");

        assertThat(item.getContent()).isEqualTo("카메라 충전기");
    }

    @Test
    @DisplayName("개인 체크리스트 체크 토글 성공")
    void editChecked_success() {
        PlanPersonalChecklistItem item = item(USER_ID);
        given(personalChecklistItemRepository.findByPersonalChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(item));

        personalChecklistService.editChecked(PLAN_ID, USER_ID, ITEM_ID, true);

        assertThat(item.isChecked()).isTrue();
    }

    @Test
    @DisplayName("개인 체크리스트 항목 수정 실패 - 항목 없음")
    void editContent_fail_itemNotFound() {
        given(personalChecklistItemRepository.findByPersonalChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> personalChecklistService.editContent(PLAN_ID, USER_ID, ITEM_ID, "x"))
                .isInstanceOf(ChecklistItemNotFoundException.class);
    }

    @Test
    @DisplayName("개인 체크리스트 항목 수정 실패 - 본인 소유가 아님")
    void editContent_fail_notOwner() {
        PlanPersonalChecklistItem item = item(OTHER_USER_ID);
        given(personalChecklistItemRepository.findByPersonalChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(item));

        assertThatThrownBy(() -> personalChecklistService.editContent(PLAN_ID, USER_ID, ITEM_ID, "x"))
                .isInstanceOf(ChecklistItemAccessDeniedException.class);
    }

    @Test
    @DisplayName("개인 체크리스트 항목 삭제 성공")
    void deleteItem_success() {
        PlanPersonalChecklistItem item = item(USER_ID);
        given(personalChecklistItemRepository.findByPersonalChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(item));

        personalChecklistService.deleteItem(PLAN_ID, USER_ID, ITEM_ID);

        verify(personalChecklistItemRepository).delete(item);
    }

    @Test
    @DisplayName("개인 체크리스트 항목 삭제 실패 - 본인 소유가 아니면 삭제 호출 자체가 안 된다")
    void deleteItem_fail_notOwner() {
        PlanPersonalChecklistItem item = item(OTHER_USER_ID);
        given(personalChecklistItemRepository.findByPersonalChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(item));

        assertThatThrownBy(() -> personalChecklistService.deleteItem(PLAN_ID, USER_ID, ITEM_ID))
                .isInstanceOf(ChecklistItemAccessDeniedException.class);
        verify(personalChecklistItemRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("개인 체크리스트 순서 변경 성공 - 목록 순서대로 sortOrder가 매겨진다")
    void reorder_success() {
        PlanPersonalChecklistItem first = item(USER_ID);
        PlanPersonalChecklistItem second = PlanPersonalChecklistItem.builder()
                .personalChecklistItemId(2L)
                .plan(plan())
                .user(user(USER_ID))
                .content("우산")
                .isChecked(false)
                .sortOrder(1)
                .build();
        given(personalChecklistItemRepository.findByPersonalChecklistItemIdAndPlanPlanId(2L, PLAN_ID))
                .willReturn(Optional.of(second));
        given(personalChecklistItemRepository.findByPersonalChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(first));

        personalChecklistService.reorder(PLAN_ID, USER_ID, List.of(2L, ITEM_ID));

        assertThat(second.getSortOrder()).isEqualTo(0);
        assertThat(first.getSortOrder()).isEqualTo(1);
    }
}
