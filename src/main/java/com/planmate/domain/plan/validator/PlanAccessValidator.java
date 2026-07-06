package com.planmate.domain.plan.validator;

import com.planmate.common.exception.domain.PlanAccessDeniedException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PlanAccessValidator {

    private final PlanMemberRepository planMemberRepository;
    private final PlanRepository planRepository;

    // OWNER 또는 EDITOR — 플랜 조회·편집 공통 검증
    // 존재 여부를 먼저 확인해서 "없음"(404)과 "권한 없음"(403)을 구분한다 —
    // @PreAuthorize가 메서드 본문보다 먼저 실행되므로, 이 순서를 지키지 않으면
    // 존재하지 않는 planId도 멤버가 아니라는 이유로 항상 403이 되어 404가 도달 불가능해진다
    // (B_조회.md 10-5절 근거).
    public boolean validateMember(UUID planId, UUID userId) {
        if (!planRepository.existsById(planId)) {
            throw new PlanNotFoundException();
        }
        if (!planMemberRepository.existsByPlanPlanIdAndUserUserId(planId, userId)) {
            throw new PlanAccessDeniedException();
        }
        return true;
    }

    // OWNER 전용 — 플랜 삭제, 멤버 추가/제거 등
    public boolean validateOwner(UUID planId, UUID userId) {
        if (!planRepository.existsById(planId)) {
            throw new PlanNotFoundException();
        }
        if (!planMemberRepository.existsByPlanPlanIdAndUserUserIdAndRole(
                planId, userId, PlanMemberRole.OWNER)) {
            throw new PlanAccessDeniedException();
        }
        return true;
    }
}
