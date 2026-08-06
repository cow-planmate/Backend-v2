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
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanPersonalChecklistService {

    private final PlanPersonalChecklistItemRepository personalChecklistItemRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional(readOnly = true)
    public GetChecklistResponse getChecklist(UUID planId, UUID userId) {
        List<ChecklistItemDto> items = personalChecklistItemRepository
                .findByPlanPlanIdAndUserUserIdOrderBySortOrder(planId, userId).stream()
                .map(ChecklistItemDto::from)
                .toList();
        return new GetChecklistResponse(items);
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public CreateChecklistItemResponse createItem(UUID planId, UUID userId, String content) {
        Plan plan = planRepository.findById(planId).orElseThrow(PlanNotFoundException::new);
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        long nextSortOrder = personalChecklistItemRepository.countByPlanPlanIdAndUserUserId(planId, userId);

        PlanPersonalChecklistItem saved = personalChecklistItemRepository.save(PlanPersonalChecklistItem.builder()
                .plan(plan)
                .user(user)
                .content(content)
                .isChecked(false)
                .sortOrder((int) nextSortOrder)
                .build());

        return new CreateChecklistItemResponse(saved.getPersonalChecklistItemId());
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public void editContent(UUID planId, UUID userId, Long itemId, String content) {
        PlanPersonalChecklistItem item = getOwnedItem(planId, userId, itemId);
        item.editContent(content);
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public void editChecked(UUID planId, UUID userId, Long itemId, boolean isChecked) {
        PlanPersonalChecklistItem item = getOwnedItem(planId, userId, itemId);
        item.changeChecked(isChecked);
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public void deleteItem(UUID planId, UUID userId, Long itemId) {
        PlanPersonalChecklistItem item = getOwnedItem(planId, userId, itemId);
        personalChecklistItemRepository.delete(item);
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public void reorder(UUID planId, UUID userId, List<Long> itemIds) {
        for (int i = 0; i < itemIds.size(); i++) {
            PlanPersonalChecklistItem item = getOwnedItem(planId, userId, itemIds.get(i));
            item.changeSortOrder(i);
        }
    }

    // itemId가 planId 소속인지(존재 여부, 404) 먼저 확인한 뒤, 요청자 본인 소유인지(403)를 확인한다 —
    // PlanAccessValidator와 동일하게 "없음"과 "권한 없음"을 구분한다.
    private PlanPersonalChecklistItem getOwnedItem(UUID planId, UUID userId, Long itemId) {
        PlanPersonalChecklistItem item = personalChecklistItemRepository
                .findByPersonalChecklistItemIdAndPlanPlanId(itemId, planId)
                .orElseThrow(ChecklistItemNotFoundException::new);
        if (!item.getUser().getUserId().equals(userId)) {
            throw new ChecklistItemAccessDeniedException();
        }
        return item;
    }
}
