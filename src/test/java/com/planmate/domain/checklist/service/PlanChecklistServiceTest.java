package com.planmate.domain.checklist.service;

import com.planmate.common.exception.domain.ChecklistItemNotFoundException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.domain.checklist.dto.ChecklistItemDto;
import com.planmate.domain.checklist.dto.CreateChecklistItemResponse;
import com.planmate.domain.checklist.dto.GetChecklistResponse;
import com.planmate.domain.checklist.entity.PlanChecklistItem;
import com.planmate.domain.checklist.repository.PlanChecklistItemRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.repository.PlanRepository;
import com.sharedsync.shared.storage.PresenceStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sharedsync.cache.PlanCache;
import sharedsync.cache.PlanChecklistItemCache;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlanChecklistServiceTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final Long ITEM_ID = 1L;

    @Mock
    private PlanChecklistItemRepository checklistItemRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private PlanCache planCache;
    @Mock
    private PlanChecklistItemCache checklistItemCache;
    @Mock
    private PresenceStorage presenceStorage;

    @InjectMocks
    private PlanChecklistService planChecklistService;

    private Plan plan() {
        return Plan.builder().planId(PLAN_ID).build();
    }

    private PlanChecklistItem item(int sortOrder) {
        return PlanChecklistItem.builder()
                .checklistItemId(ITEM_ID)
                .plan(plan())
                .content("여권")
                .isChecked(false)
                .sortOrder(sortOrder)
                .build();
    }

    @Test
    @DisplayName("공유 체크리스트 조회 성공 - 캐시 미존재 시 DB로 폴백한다")
    void getChecklist_success_dbFallback() {
        given(planCache.findById(PLAN_ID)).willReturn(Optional.empty());
        given(checklistItemRepository.findByPlanPlanIdOrderBySortOrder(PLAN_ID)).willReturn(List.of(item(0)));

        GetChecklistResponse response = planChecklistService.getChecklist(PLAN_ID);

        assertThat(response.items()).hasSize(1);
        verify(presenceStorage).waitForSync(PLAN_ID.toString());
        verify(checklistItemRepository).findByPlanPlanIdOrderBySortOrder(PLAN_ID);
    }

    @Test
    @DisplayName("공유 체크리스트 조회 성공 - 캐시 존재 시 캐시에서 읽고 sortOrder로 재정렬한다")
    void getChecklist_success_cachePath_sortsBySortOrder() {
        PlanChecklistItem second = item(1);
        PlanChecklistItem first = PlanChecklistItem.builder()
                .checklistItemId(2L).plan(plan()).content("카메라").isChecked(false).sortOrder(0).build();

        given(planCache.findById(PLAN_ID)).willReturn(Optional.of(plan()));
        // 캐시는 순서를 보장하지 않는다는 걸 재현하기 위해 일부러 뒤바꿔서 반환
        given(checklistItemCache.findByParentId(PLAN_ID)).willReturn(List.of(second, first));

        GetChecklistResponse response = planChecklistService.getChecklist(PLAN_ID);

        assertThat(response.items()).extracting(ChecklistItemDto::itemId).containsExactly(2L, ITEM_ID);
    }

    @Test
    @DisplayName("공유 체크리스트 항목 생성 성공 - sortOrder는 기존 개수만큼 뒤에 붙는다")
    void createItem_success() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan()));
        given(checklistItemRepository.countByPlanPlanId(PLAN_ID)).willReturn(3L);
        given(checklistItemRepository.save(ArgumentMatchers.any(PlanChecklistItem.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ArgumentCaptor<PlanChecklistItem> captor = ArgumentCaptor.forClass(PlanChecklistItem.class);

        CreateChecklistItemResponse response = planChecklistService.createItem(PLAN_ID, "우산");

        verify(checklistItemRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("우산");
        assertThat(captor.getValue().getSortOrder()).isEqualTo(3);
        assertThat(response).isNotNull();
        // 편집 세션(캐시)이 없으므로 캐시 write-through는 시도하지 않는다
        verify(checklistItemCache, never()).save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("공유 체크리스트 항목 생성 실패 - 플랜 없음")
    void createItem_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> planChecklistService.createItem(PLAN_ID, "우산"))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    @DisplayName("공유 체크리스트 항목 내용 수정 성공")
    void editContent_success() {
        PlanChecklistItem item = item(0);
        given(checklistItemRepository.findByChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(item));

        planChecklistService.editContent(PLAN_ID, ITEM_ID, "카메라 충전기");

        assertThat(item.getContent()).isEqualTo("카메라 충전기");
        // 편집 세션(캐시)에 이 항목이 없으므로 캐시 write-through는 시도하지 않는다
        verify(checklistItemCache, never()).save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("공유 체크리스트 항목 수정 실패 - 항목 없음")
    void editContent_fail_itemNotFound() {
        given(checklistItemRepository.findByChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> planChecklistService.editContent(PLAN_ID, ITEM_ID, "x"))
                .isInstanceOf(ChecklistItemNotFoundException.class);
    }

    @Test
    @DisplayName("공유 체크리스트 체크 토글 성공")
    void editChecked_success() {
        PlanChecklistItem item = item(0);
        given(checklistItemRepository.findByChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(item));

        planChecklistService.editChecked(PLAN_ID, ITEM_ID, true);

        assertThat(item.isChecked()).isTrue();
    }

    @Test
    @DisplayName("공유 체크리스트 항목 삭제 성공")
    void deleteItem_success() {
        PlanChecklistItem item = item(0);
        given(checklistItemRepository.findByChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(item));

        planChecklistService.deleteItem(PLAN_ID, ITEM_ID);

        verify(checklistItemRepository).delete(item);
        verify(checklistItemCache, never()).deleteById(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("공유 체크리스트 순서 변경 성공 - 목록 순서대로 sortOrder가 매겨진다")
    void reorder_success() {
        PlanChecklistItem first = item(0);
        PlanChecklistItem second = PlanChecklistItem.builder()
                .checklistItemId(2L).plan(plan()).content("우산").isChecked(false).sortOrder(1).build();

        given(checklistItemRepository.findByChecklistItemIdAndPlanPlanId(2L, PLAN_ID))
                .willReturn(Optional.of(second));
        given(checklistItemRepository.findByChecklistItemIdAndPlanPlanId(ITEM_ID, PLAN_ID))
                .willReturn(Optional.of(first));

        planChecklistService.reorder(PLAN_ID, List.of(2L, ITEM_ID));

        assertThat(second.getSortOrder()).isEqualTo(0);
        assertThat(first.getSortOrder()).isEqualTo(1);
    }
}
