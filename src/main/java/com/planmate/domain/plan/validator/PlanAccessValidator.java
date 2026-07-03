package com.planmate.domain.plan.validator;

import com.planmate.common.exception.domain.PlanAccessDeniedException;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PlanAccessValidator {

    private final PlanMemberRepository planMemberRepository;

    // OWNER 또는 EDITOR — 플랜 조회·편집 공통 검증
    public void validateMember(UUID planId, UUID userId) {
        if (!planMemberRepository.existsByPlanPlanIdAndUserUserId(planId, userId)) {
            throw new PlanAccessDeniedException();
        }
    }

    // OWNER 전용 — 플랜 삭제, 멤버 추가/제거 등
    public void validateOwner(UUID planId, UUID userId) {
        if (!planMemberRepository.existsByPlanPlanIdAndUserUserIdAndRole(
                planId, userId, PlanMemberRole.OWNER)) {
            throw new PlanAccessDeniedException();
        }
    }
}
