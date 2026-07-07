package com.planmate.domain.plan.repository;

import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanMemberRepository extends JpaRepository<PlanMember, Long> {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 권한 확인 (PlanAccessValidator 전용)
    // 활용 인덱스: UNIQUE (user_id, plan_id) — user_id 선두 컬럼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // OWNER 또는 EDITOR — 플랜 접근 권한 확인
    boolean existsByPlanPlanIdAndUserUserId(UUID planId, UUID userId);

    // OWNER 전용 확인 — 플랜 삭제, 멤버 추가/제거 등
    boolean existsByPlanPlanIdAndUserUserIdAndRole(UUID planId, UUID userId, PlanMemberRole role);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 마이페이지
    // 활용 인덱스: UNIQUE (user_id, plan_id) — user_id 선두 → 단독 조회 가능
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // 내가 속한 플랜 목록 (OWNER + EDITOR 통합) — plan, plan.destination 즉시 로딩
    // Plan은 @SQLRestriction 적용 → 소프트 삭제된 플랜 자동 제외
    @EntityGraph(attributePaths = {"plan", "plan.destination"})
    List<PlanMember> findByUserUserId(UUID userId);

    // 역할 필터링이 필요한 경우 (예: 소유한 플랜만)
    @EntityGraph(attributePaths = {"plan", "plan.destination"})
    List<PlanMember> findByUserUserIdAndRole(UUID userId, PlanMemberRole role);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 플랜 멤버 관리
    // 활용 인덱스: idx_plan_member_plan_id — UNIQUE의 후행 컬럼(plan_id)만으로 조회 불가
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // 플랜 멤버 목록 조회 (user 정보 즉시 로딩)
    @EntityGraph(attributePaths = {"user"})
    List<PlanMember> findByPlanPlanId(UUID planId);

    // 특정 멤버 단건 조회 (role 변경, 멤버 정보 조회)
    Optional<PlanMember> findByPlanPlanIdAndUserUserId(UUID planId, UUID userId);

    // 멤버 제거
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PlanMember pm WHERE pm.plan.planId = :planId AND pm.user.userId = :userId")
    void deleteByPlanPlanIdAndUserUserId(@Param("planId") UUID planId, @Param("userId") UUID userId);

    // 플랜 멤버 수 (편집자 추가 상한선 검증 등)
    long countByPlanPlanId(UUID planId);

    // 플랜 다중 삭제(#30) — 요청한 planId 중 이 사용자가 OWNER인 것만 조회, plan 즉시 로딩(softDelete() 호출 위해)
    // 존재하지 않거나 이미 소프트 삭제된 플랜은 Plan의 @SQLRestriction으로 자동 제외됨
    @EntityGraph(attributePaths = {"plan"})
    List<PlanMember> findByUserUserIdAndRoleAndPlanPlanIdIn(UUID userId, PlanMemberRole role, List<UUID> planIds);

    // 본인 편집 권한 포기(#34) / 편집자 권한 박탈(#35) 공용 — 이 사용자가 이 플랜에서 정확히 이 역할인지 확인
    // role 조건이 있어 OWNER 행은 절대 걸리지 않음(plan_member/A_권한.md 10-1절 근거)
    Optional<PlanMember> findByPlanPlanIdAndUserUserIdAndRole(UUID planId, UUID userId, PlanMemberRole role);

    // 편집자 목록 조회(#36) — role=EDITOR인 멤버만, user 즉시 로딩(N+1 방지)
    @EntityGraph(attributePaths = {"user"})
    List<PlanMember> findByPlanPlanIdAndRole(UUID planId, PlanMemberRole role);
}
