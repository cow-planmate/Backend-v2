package com.planmate.domain.checklist.service;

import com.planmate.domain.checklist.dto.CreateChecklistItemResponse;
import com.planmate.domain.checklist.entity.PlanChecklistItem;
import com.planmate.domain.checklist.repository.PlanChecklistItemRepository;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
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
import sharedsync.cache.PlanChecklistItemCache;
import sharedsync.dto.PlanChecklistItemDto;
import sharedsync.dto.PlanDto;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공유 체크리스트 REST 쓰기가 SharedSync 캐시(=실시간 편집 세션)와 어떻게 상호작용하는지 검증한다.
 * PlanNameCacheWriteThroughTest와 동일한 원칙: 편집 세션 중인 플랜/항목이면 REST 변경이
 * 캐시에도 즉시 반영되어야, 이후 주기 동기화가 그 stale 캐시를 DB로 flush할 때 REST 변경이
 * 되돌아가지 않는다.
 */
@SpringBootTest
@Transactional
class PlanChecklistCacheWriteThroughTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DestinationRepository destinationRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanMemberRepository planMemberRepository;
    @Autowired
    private PlanChecklistItemRepository checklistItemRepository;
    @Autowired
    private PlanCache planCache;
    @Autowired
    private PlanChecklistItemCache checklistItemCache;
    @Autowired
    private PlanChecklistService planChecklistService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private User createOwner() {
        return userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("checklist-cache-" + UUID.randomUUID() + "@example.com")
                .nickname("cc-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
    }

    private Plan createPlan(User owner) {
        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();
        Plan plan = planRepository.saveAndFlush(Plan.builder()
                .planName("체크리스트 캐시 테스트")
                .adultCount(2)
                .childCount(0)
                .isShared(true)
                .user(owner)
                .destination(destination)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan).user(owner).role(PlanMemberRole.OWNER).build());
        return plan;
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getUserId(), null, List.of()));
    }

    @Test
    @DisplayName("편집 세션 중인 플랜에 REST로 항목을 추가하면, DB가 발급한 id 그대로 캐시에도 반영된다")
    void createItem_activeSession_writesToCacheWithSameId() {
        User owner = createOwner();
        Plan plan = createPlan(owner);
        planCache.save(PlanDto.fromEntity(plan));
        authenticateAs(owner);

        CreateChecklistItemResponse response = planChecklistService.createItem(plan.getPlanId(), "여권");

        assertThat(checklistItemRepository.findById(response.itemId())).isPresent();
        assertThat(checklistItemCache.findById(response.itemId()))
                .as("캐시에도 DB와 동일한 id로 항목이 반영되어야 한다")
                .isPresent();
    }

    @Test
    @DisplayName("편집 세션이 없는 플랜에 REST로 항목을 추가하면, 캐시에는 반영되지 않는다")
    void createItem_noActiveSession_doesNotTouchCache() {
        User owner = createOwner();
        Plan plan = createPlan(owner);
        authenticateAs(owner);
        // planCache에 저장하지 않음 = 편집 세션이 열려 있지 않은 상태

        CreateChecklistItemResponse response = planChecklistService.createItem(plan.getPlanId(), "여권");

        assertThat(checklistItemRepository.findById(response.itemId())).isPresent();
        assertThat(checklistItemCache.findById(response.itemId())).isEmpty();
    }

    @Test
    @DisplayName("편집 세션 중인 항목을 REST로 수정하면, 캐시에만 반영돼 있던 다른 필드는 보존된다")
    void editContent_activeSession_preservesOtherCachedFields() {
        User owner = createOwner();
        Plan plan = createPlan(owner);
        PlanChecklistItem item = checklistItemRepository.saveAndFlush(PlanChecklistItem.builder()
                .plan(plan).content("옛 내용").isChecked(false).sortOrder(0).build());

        planCache.save(PlanDto.fromEntity(plan));
        // 캐시에는 아직 DB에 동기화되지 않은 실시간 편집 결과(sortOrder=5)가 있다고 가정
        checklistItemCache.save(new PlanChecklistItemDto(
                item.getChecklistItemId(), plan.getPlanId(), "옛 내용", false, 5));
        authenticateAs(owner);

        planChecklistService.editContent(plan.getPlanId(), item.getChecklistItemId(), "새 내용");

        assertThat(checklistItemRepository.findById(item.getChecklistItemId()).orElseThrow().getContent())
                .as("DB는 즉시 반영되어야 한다")
                .isEqualTo("새 내용");
        assertThat(checklistItemCache.findById(item.getChecklistItemId()).orElseThrow().getContent())
                .as("캐시도 같이 갱신되어야 한다")
                .isEqualTo("새 내용");
        assertThat(checklistItemCache.findById(item.getChecklistItemId()).orElseThrow().getSortOrder())
                .as("REST가 건드리지 않은 필드(sortOrder)는 캐시에 남아있던 실시간 편집 결과가 보존되어야 한다")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("편집 세션 중인 항목을 REST로 삭제하면 캐시에서도 같이 제거된다")
    void deleteItem_activeSession_removesFromCache() {
        User owner = createOwner();
        Plan plan = createPlan(owner);
        PlanChecklistItem item = checklistItemRepository.saveAndFlush(PlanChecklistItem.builder()
                .plan(plan).content("여권").isChecked(false).sortOrder(0).build());

        planCache.save(PlanDto.fromEntity(plan));
        checklistItemCache.save(PlanChecklistItemDto.fromEntity(item));
        authenticateAs(owner);

        planChecklistService.deleteItem(plan.getPlanId(), item.getChecklistItemId());

        assertThat(checklistItemRepository.findById(item.getChecklistItemId())).isEmpty();
        assertThat(checklistItemCache.findById(item.getChecklistItemId())).isEmpty();
    }
}
