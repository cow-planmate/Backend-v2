package com.planmate.domain.plan.service;

import com.planmate.common.exception.domain.AuthenticationRequiredException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.dto.GetPlanResponse;
import com.planmate.domain.plan.dto.GetShareStatusResponse;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.plan.validator.PlanAccessValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sharedsync.cache.PlanCache;
import sharedsync.dto.PlanDto;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanRepository planRepository;
    private final PlanAccessValidator planAccessValidator;
    private final PlanSnapshotReader planSnapshotReader;
    private final PlanCache planCache;

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional(readOnly = true)
    public GetPlanResponse getPlan(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);

        // 실시간 편집 중이면 최신 상태는 DB가 아니라 SharedSync 캐시에 있다. PlanSnapshotReader 참고.
        PlanSnapshotReader.PlanSnapshot snapshot = planSnapshotReader.read(plan);

        return new GetPlanResponse(snapshot.planFrame(), snapshot.timetables(), snapshot.placeBlocks());
    }

    @Transactional(readOnly = true)
    public GetCompletePlanResponse getCompletePlan(UUID userId, UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);

        if (!plan.isShared()) {
            if (userId == null) {
                throw new AuthenticationRequiredException();
            }
            planAccessValidator.validateMember(planId, userId);
        }

        // 편집 화면에서 "완료"로 넘어온 직후에는 수정분이 아직 DB에 없고 캐시에만 있다. PlanSnapshotReader 참고.
        PlanSnapshotReader.PlanSnapshot snapshot = planSnapshotReader.read(plan);

        return new GetCompletePlanResponse(snapshot.planFrame(), snapshot.timetables(), snapshot.placeBlocks());
    }

    @PreAuthorize("@planAccessValidator.validateOwner(#planId, authentication.principal)")
    @Transactional
    public void deletePlan(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);
        plan.softDelete();
    }

    @PreAuthorize("@planAccessValidator.validateOwner(#planId, authentication.principal)")
    @Transactional
    public void editPlanName(UUID planId, String planName) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);
        plan.rename(planName);

        // 이 플랜이 SharedSync 캐시에 떠 있으면(=실시간 편집 세션이 열려 있으면) 캐시도 같이 갱신한다.
        // 안 그러면 편집 세션의 stale 캐시가 나중에 주기 동기화로 flush될 때 이 이름 변경이 되돌아간다.
        // 캐시 조회/저장 실패(Redis 장애 등)가 이름변경 자체를 막지 않도록 격리한다.
        try {
            planCache.findById(planId).ifPresent(cachedPlan ->
                    planCache.save(PlanDto.fromEntity(cachedPlan.toBuilder().planName(planName).build())));
        } catch (Exception e) {
            log.warn("[Plan] 이름변경 후 SharedSync 캐시 갱신 실패 (DB는 정상 반영됨). planId={}", planId, e);
        }
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional(readOnly = true)
    public GetShareStatusResponse getShareStatus(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);
        return new GetShareStatusResponse(plan.isShared());
    }

    @PreAuthorize("@planAccessValidator.validateOwner(#planId, authentication.principal)")
    @Transactional
    public void updateShare(UUID planId, boolean isShared) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);
        if (isShared) {
            plan.share();
        } else {
            plan.unshare();
        }
    }
}
