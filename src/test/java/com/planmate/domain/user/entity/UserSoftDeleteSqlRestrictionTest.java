package com.planmate.domain.user.entity;

import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User의 {@code @SQLRestriction("deleted_at IS NULL")}이 User를 직접 조회할 때뿐 아니라,
 * 다른 엔티티가 User를 연관관계(EntityGraph join)로 가져올 때도 동일하게 적용되는지 확인한다.
 * 이 보장이 깨지면 탈퇴한 유저가 다른 도메인(플랜 멤버 목록 등)에 계속 노출될 수 있다.
 */
@SpringBootTest
@Transactional
class UserSoftDeleteSqlRestrictionTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private PlanMemberRepository planMemberRepository;
    @Autowired private DestinationRepository destinationRepository;

    @Test
    @DisplayName("탈퇴(소프트 삭제)한 유저는 다른 엔티티가 User를 join으로 가져오는 조회(PlanMember 목록)에서도 자동으로 제외된다")
    void softDeletedUser_isExcludedFromJoinedQueries() {
        User owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("owner-" + UUID.randomUUID() + "@example.com")
                .nickname("owner-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        User editor = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("editor-" + UUID.randomUUID() + "@example.com")
                .nickname("editor-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());

        Destination destination = destinationRepository.save(Destination.builder()
                .destinationName("dest-" + UUID.randomUUID())
                .build());
        Plan plan = planRepository.save(Plan.builder()
                .planName("test plan")
                .adultCount(1)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(true)
                .user(owner)
                .destination(destination)
                .build());
        planMemberRepository.save(PlanMember.builder().plan(plan).user(owner).role(PlanMemberRole.OWNER).build());
        planMemberRepository.save(PlanMember.builder().plan(plan).user(editor).role(PlanMemberRole.EDITOR).build());

        assertThat(planMemberRepository.findByPlanPlanId(plan.getPlanId())).hasSize(2);

        editor.softDelete();
        userRepository.saveAndFlush(editor);

        List<PlanMember> remaining = planMemberRepository.findByPlanPlanId(plan.getPlanId());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getRole()).isEqualTo(PlanMemberRole.OWNER);
    }
}
