package com.planmate.domain.checklist.repository;

import com.planmate.domain.checklist.entity.PlanPersonalChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanPersonalChecklistItemRepository extends JpaRepository<PlanPersonalChecklistItem, Long> {

    // 요청자 본인의 개인 체크리스트 목록 (순서대로)
    // 활용 인덱스: idx_plan_personal_checklist_item_plan_user
    List<PlanPersonalChecklistItem> findByPlanPlanIdAndUserUserIdOrderBySortOrder(UUID planId, UUID userId);

    // itemId가 요청한 planId 소속인지까지 확인 (소유자 확인은 Service에서 별도로 해서 404/403을 구분한다)
    Optional<PlanPersonalChecklistItem> findByPersonalChecklistItemIdAndPlanPlanId(
            Long personalChecklistItemId, UUID planId);

    // 새 항목의 sortOrder(맨 뒤에 추가) 계산용
    long countByPlanPlanIdAndUserUserId(UUID planId, UUID userId);
}
