package com.planmate.domain.plan.service;

import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import sharedsync.cache.PlanCache;
import sharedsync.dto.PlanDto;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * editPlanName()이 REST로 DB를 직접 고칠 때, 이미 SharedSync 캐시에 떠 있는(=Create2 편집 세션이
 * 열려 있는) 이 플랜의 stale 캐시도 같이 갱신하는지 검증한다. 갱신하지 않으면 나중에 주기 동기화가
 * 그 stale 캐시를 DB에 flush할 때 이름 변경이 되돌아간다(isShared 회귀와 같은 패턴).
 */
@SpringBootTest
@Transactional
class PlanNameCacheWriteThroughTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DestinationRepository destinationRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanMemberRepository planMemberRepository;
    @Autowired
    private PlanCache planCache;
    @Autowired
    private PlanService planService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("편집 세션 중인 플랜을 REST로 개명하면, 캐시도 같이 갱신되어 이후 주기 동기화가 되돌리지 않는다")
    void editPlanName_updatesActiveCacheEntry_soLaterFlushDoesNotRevertIt() {
        User owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("name-regress-" + UUID.randomUUID() + "@example.com")
                .nickname("nr-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());

        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();

        Plan plan = planRepository.saveAndFlush(Plan.builder()
                .planName("옛날 이름")
                .adultCount(2)
                .childCount(1)
                .transportationType(TransportationType.PUBLIC)
                .isShared(false)
                .user(owner)
                .destination(destination)
                .build());
        UUID planId = plan.getPlanId();
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(owner)
                .role(PlanMemberRole.OWNER)
                .build());

        // 1) Create2 편집 세션이 열려 이 플랜이 SharedSync 캐시에 실렸다고 가정
        planCache.save(PlanDto.fromEntity(plan));
        assertThat(planCache.findById(planId)).isPresent();

        // 2) 그 사이 마이페이지에서 이름을 바꿈 (PATCH /plans/{id}/name, @PreAuthorize 통과를 위해 오너로 인증)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner.getUserId(), null, List.of()));
        planService.editPlanName(planId, "새 이름");

        // 3) DB는 물론 캐시도 새 이름으로 갱신돼 있어야 한다
        assertThat(planRepository.findById(planId).orElseThrow().getPlanName())
                .as("DB는 즉시 반영되어야 한다")
                .isEqualTo("새 이름");
        assertThat(planCache.findById(planId).orElseThrow().getPlanName())
                .as("캐시도 같이 갱신되어야 한다 - 안 그러면 다음 flush 때 되돌아간다")
                .isEqualTo("새 이름");
        // 다른 필드는 캐시 갱신 과정에서 훼손되면 안 된다 (0으로 밀림 등)
        assertThat(planCache.findById(planId).orElseThrow().getAdultCount()).isEqualTo(2);
        assertThat(planCache.findById(planId).orElseThrow().getChildCount()).isEqualTo(1);

        // 4) 편집 세션이 끝나며 주기 동기화가 캐시를 DB로 flush
        planCache.syncToDatabaseByDto(planCache.findDtoById(planId));

        // 5) flush 이후에도 이름이 되돌아가지 않아야 한다
        assertThat(planRepository.findById(planId).orElseThrow().getPlanName())
                .as("stale 캐시 flush가 REST로 바뀐 이름을 되돌리면 안 된다")
                .isEqualTo("새 이름");
    }
}
