package com.planmate.domain.plan.service;

import com.planmate.common.exception.domain.PlanAccessDeniedException;
import com.planmate.common.exception.domain.PlanMemberNotFoundException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.domain.plan.dto.DeleteMultiplePlansResponse;
import com.planmate.domain.plan.dto.EditorDto;
import com.planmate.domain.plan.dto.GetEditorsResponse;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanMembershipService {

    private final PlanRepository planRepository;
    private final PlanMemberRepository planMemberRepository;

    @Transactional
    public DeleteMultiplePlansResponse deleteMultiplePlans(UUID userId, List<UUID> planIds) {
        List<PlanMember> ownedMemberships = planMemberRepository
                .findByUserUserIdAndRoleAndPlanPlanIdIn(userId, PlanMemberRole.OWNER, planIds);

        if (ownedMemberships.isEmpty()) {
            throw new PlanAccessDeniedException();
        }

        List<UUID> deletedPlanIds = ownedMemberships.stream()
                .map(PlanMember::getPlan)
                .peek(Plan::softDelete)
                .map(Plan::getPlanId)
                .toList();

        return new DeleteMultiplePlansResponse(deletedPlanIds);
    }

    @Transactional
    public void resignEditorAccess(UUID planId, UUID userId) {
        if (!planRepository.existsById(planId)) {
            throw new PlanNotFoundException();
        }
        PlanMember member = planMemberRepository
                .findByPlanPlanIdAndUserUserIdAndRole(planId, userId, PlanMemberRole.EDITOR)
                .orElseThrow(PlanMemberNotFoundException::new);
        planMemberRepository.delete(member);
    }

    @PreAuthorize("@planAccessValidator.validateOwner(#planId, authentication.principal)")
    @Transactional
    public void removeEditorAccessByOwner(UUID planId, UUID targetUserId) {
        PlanMember member = planMemberRepository
                .findByPlanPlanIdAndUserUserIdAndRole(planId, targetUserId, PlanMemberRole.EDITOR)
                .orElseThrow(PlanMemberNotFoundException::new);
        planMemberRepository.delete(member);
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional(readOnly = true)
    public GetEditorsResponse getEditors(UUID planId) {
        List<EditorDto> editors = planMemberRepository.findByPlanPlanIdAndRole(planId, PlanMemberRole.EDITOR).stream()
                .map(member -> new EditorDto(member.getUser().getUserId(), member.getUser().getNickname()))
                .toList();
        return new GetEditorsResponse(editors);
    }
}
