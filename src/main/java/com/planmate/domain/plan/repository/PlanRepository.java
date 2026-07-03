package com.planmate.domain.plan.repository;

import com.planmate.domain.plan.entity.Plan;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    // 소유자 기준 플랜 목록 (admin 또는 OWNER 전용 조회) — destination(이름/좌표) 즉시 로딩, N+1 방지
    // 활용 인덱스: idx_plan_user_id ON (user_id) WHERE deleted_at IS NULL
    // @SQLRestriction이 deleted_at IS NULL 자동 삽입
    @EntityGraph(attributePaths = {"destination"})
    List<Plan> findByUserUserId(UUID userId);

    // JpaRepository 기본 제공:
    // findById(UUID planId) — @SQLRestriction 적용 → 소프트 삭제 플랜은 Optional.empty() 반환
    // deleteById(UUID planId) — 실제 DELETE 실행 (소프트 삭제 아님, Service에서 softDelete() 사용할 것)
}
