package com.planmate.domain.plan.service;

import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import sharedsync.cache.PlanCache;
import sharedsync.dto.PlanDto;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트랜잭션 밖에서 불러도 캐시에 뜬 플랜을 캐시로 읽는지 — 챗봇 [반영]이 이 경로다.
 *
 * <p>캐시에서 복원한 Plan 의 Destination 은 {@code em.getReference()} 프록시라, 세션 없이 이름을 꺼내면
 * {@code LazyInitializationException} 으로 DB 폴백했다. DB 는 주기 동기화(30초)만큼 늦어 [반영]을 연속하면
 * 블록이 통째로 복제됐다(project_ai/04_기능/ai/07_반영_reconcile_설계.md §12-2).
 *
 * <p>일부러 {@code @Transactional} 을 붙이지 않는다 — 붙이면 테스트 트랜잭션이 세션을 열어 둬서 재현되지 않는다.
 */
@SpringBootTest
class PlanSnapshotReaderCacheProxyTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DestinationRepository destinationRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanCache planCache;
    @Autowired
    private PlanSnapshotReader planSnapshotReader;

    private User owner;
    private UUID planId;

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("snapshot-proxy-" + UUID.randomUUID() + "@example.com")
                .nickname("sp-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        Destination busan = destinationRepository.findByDestinationName("부산").orElseThrow();
        Plan plan = planRepository.saveAndFlush(Plan.builder()
                .planName("캐시 이름")
                .adultCount(2)
                .childCount(0)
                .isShared(false)
                .user(owner)
                .destination(busan)
                .build());
        planId = plan.getPlanId();

        // 편집 세션이 열려 이 플랜이 SharedSync 캐시에 실렸다고 가정
        planCache.save(PlanDto.fromEntity(plan));
    }

    @AfterEach
    void tearDown() {
        planCache.deleteCacheOnlyById(planId);
        planRepository.findById(planId).ifPresent(planRepository::delete);
        userRepository.delete(owner);
    }

    @Test
    @DisplayName("트랜잭션 밖 호출 — 캐시 Plan 의 Destination 프록시를 채워 캐시로 읽는다(DB 폴백 안 함)")
    void read_outsideTransaction_readsCachedPlanThroughDestinationProxy() {
        // 전제: 캐시 Plan 의 Destination 은 아직 안 채워진 프록시 — 이게 아니면 이 테스트는 재현을 못 한다.
        assertThat(Hibernate.isInitialized(planCache.findById(planId).orElseThrow().getDestination())).isFalse();

        PlanSnapshotReader.PlanSnapshot snapshot =
                planSnapshotReader.read(planRepository.findById(planId).orElseThrow());

        assertThat(snapshot.source()).isEqualTo(PlanSnapshotReader.Source.CACHE);
        assertThat(snapshot.planFrame().destinationName()).isEqualTo("부산");
        assertThat(snapshot.planFrame().planName()).isEqualTo("캐시 이름");
    }
}
