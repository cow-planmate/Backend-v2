package com.planmate.domain.checklist.repository;

import com.planmate.domain.checklist.entity.PlanChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanChecklistItemRepository extends JpaRepository<PlanChecklistItem, Long> {

    // 플랜의 공유 체크리스트 목록 (순서대로)
    // 활용 인덱스: idx_plan_checklist_item_plan_id
    List<PlanChecklistItem> findByPlanPlanIdOrderBySortOrder(UUID planId);

    // itemId가 요청한 planId 소속인지까지 같이 확인 (다른 플랜의 itemId로 접근하는 것 방지)
    Optional<PlanChecklistItem> findByChecklistItemIdAndPlanPlanId(Long checklistItemId, UUID planId);

    // 새 항목의 sortOrder(맨 뒤에 추가) 계산용
    long countByPlanPlanId(UUID planId);
}
