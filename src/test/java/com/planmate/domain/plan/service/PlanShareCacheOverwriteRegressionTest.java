package com.planmate.domain.plan.service;

import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import sharedsync.cache.PlanCache;
import sharedsync.dto.PlanDto;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회귀 재현 테스트: 다른 사용자가 편집 화면을 열어 Plan이 SharedSync 캐시에 stale 스냅샷으로
 * 실린 상태에서, 오너가 PATCH /plans/{id}/share 로 공유를 켠 뒤, 그 stale 캐시가 주기 동기화로
 * DB에 flush되면 isShared가 되돌아가는지 실측한다.
 *
 * <p>syncToDatabaseByDto 내부의 EntityManager.merge 는 활성 트랜잭션을 요구하므로
 * (운영에서는 PeriodicSyncScheduler/disconnect-sync가 자체 트랜잭션을 열어 호출한다),
 * 테스트에서는 클래스 레벨 @Transactional 로 그 트랜잭션을 대신 제공한다.</p>
 */
@SpringBootTest
@Transactional
class PlanShareCacheOverwriteRegressionTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DestinationRepository destinationRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanCache planCache;

    private User owner;
    private Plan plan;

    @Test
    @DisplayName("stale 캐시 flush가, 그 사이 PATCH로 켜진 공유 상태를 되돌리지 않아야 한다")
    void staleCachedPlanFlush_doesNotRevertShareStatusChangedInBetween() {
        owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("share-regress-" + UUID.randomUUID() + "@example.com")
                .nickname("sr-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());

        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();

        plan = planRepository.saveAndFlush(Plan.builder()
                .planName("공유 회귀테스트 플랜")
                .adultCount(1)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(false)
                .user(owner)
                .destination(destination)
                .build());

        // 1) 다른 사용자가 편집 화면을 열어, 이 시점(isShared=false)의 Plan 스냅샷이 SharedSync 캐시에 실렸다고 가정
        PlanDto staleCachedSnapshot = PlanDto.fromEntity(plan);
        System.out.println("[REPRO] stale snapshot captured while isShared=false");

        // 2) 그 사이 오너가 공유 링크를 켬 (PATCH /plans/{id}/share) - 캐시를 거치지 않고 DB에 직접 반영
        Plan reloaded = planRepository.findById(plan.getPlanId()).orElseThrow();
        reloaded.share();
        planRepository.saveAndFlush(reloaded);

        boolean afterPatch = planRepository.findById(plan.getPlanId()).orElseThrow().isShared();
        System.out.println("[REPRO] after PATCH /share, DB isShared=" + afterPatch);
        assertThat(afterPatch).as("PATCH 직후에는 당연히 true여야 한다").isTrue();

        // 3) 편집 세션이 끝나며 PeriodicSyncScheduler(혹은 disconnect-sync)가 stale 캐시 스냅샷을 DB에 flush
        planCache.syncToDatabaseByDto(staleCachedSnapshot);

        // 4) isShared가 되돌아가지 않았는지 확인
        boolean afterStaleFlush = planRepository.findById(plan.getPlanId()).orElseThrow().isShared();
        System.out.println("[REPRO] after stale cache flush, DB isShared=" + afterStaleFlush);
        assertThat(afterStaleFlush)
                .as("stale 캐시 flush가 PATCH로 바뀐 공유 상태를 되돌리면 안 된다")
                .isTrue();
    }
}
