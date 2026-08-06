package com.planmate.domain.checklist.service;

import com.planmate.common.exception.domain.ChecklistItemNotFoundException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.domain.checklist.dto.ChecklistItemDto;
import com.planmate.domain.checklist.dto.CreateChecklistItemResponse;
import com.planmate.domain.checklist.dto.GetChecklistResponse;
import com.planmate.domain.checklist.entity.PlanChecklistItem;
import com.planmate.domain.checklist.repository.PlanChecklistItemRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.repository.PlanRepository;
import com.sharedsync.shared.storage.PresenceStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sharedsync.cache.PlanCache;
import sharedsync.cache.PlanChecklistItemCache;
import sharedsync.dto.PlanChecklistItemDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 공유 체크리스트(plan_checklist_item)는 SharedSync 실시간 편집 세션(WebSocket) 안에서도
 * 편집되지만, 마이페이지 등 세션 밖 REST 경로도 함께 제공한다. REST 쓰기는 {@code editPlanName}
 * (PlanService)과 동일한 원칙을 따른다 — DB를 먼저 반영하고, 이 플랜/항목이 SharedSync 캐시에
 * 떠 있으면(=편집 세션이 열려 있으면) 캐시도 같이 갱신한다. 캐시 반영 실패(Redis 장애 등)는
 * DB 반영 자체를 막지 않도록 격리한다.
 *
 * <p>REST로 반영한 변경은 이미 접속 중인 실시간 편집자에게 자동으로 push되지 않는다
 * ({@code RedisSyncService.publish} 브로드캐스트 미적용, 1차 구현에서 의도적으로 보류 —
 * 체크리스트_기능_설계.md 5장 참고). 다른 편집자는 방을 나갔다 재입장해야 반영된 내용을 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanChecklistService {

    private final PlanChecklistItemRepository checklistItemRepository;
    private final PlanRepository planRepository;
    private final PlanCache planCache;
    private final PlanChecklistItemCache checklistItemCache;
    private final PresenceStorage presenceStorage;

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional(readOnly = true)
    public GetChecklistResponse getChecklist(UUID planId) {
        // 세션 종료 동기화(캐시 삭제)와 DB 커밋 사이의 중간 상태를 읽지 않기 위해 대기한다.
        // PlanSnapshotReader.read()와 동일한 원칙.
        presenceStorage.waitForSync(planId.toString());

        List<PlanChecklistItem> items = planCache.findById(planId).isPresent()
                ? readWithCacheFallback(planId)
                : readFromDatabase(planId);

        List<ChecklistItemDto> dtos = items.stream().map(ChecklistItemDto::from).toList();
        return new GetChecklistResponse(dtos);
    }

    private List<PlanChecklistItem> readWithCacheFallback(UUID planId) {
        try {
            return readFromCache(planId);
        } catch (Exception e) {
            log.warn("[PlanChecklist] 캐시 조회 실패로 DB 폴백. planId={}", planId, e);
            return readFromDatabase(planId);
        }
    }

    private List<PlanChecklistItem> readFromCache(UUID planId) {
        // 캐시 조회는 순서를 보장하지 않으므로 반드시 sortOrder로 재정렬한다.
        List<PlanChecklistItem> items = new ArrayList<>(checklistItemCache.findByParentId(planId));
        items.sort(Comparator.comparingInt(PlanChecklistItem::getSortOrder));
        return items;
    }

    private List<PlanChecklistItem> readFromDatabase(UUID planId) {
        return checklistItemRepository.findByPlanPlanIdOrderBySortOrder(planId);
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public CreateChecklistItemResponse createItem(UUID planId, String content) {
        Plan plan = planRepository.findById(planId).orElseThrow(PlanNotFoundException::new);
        long nextSortOrder = checklistItemRepository.countByPlanPlanId(planId);

        PlanChecklistItem saved = checklistItemRepository.save(PlanChecklistItem.builder()
                .plan(plan)
                .content(content)
                .isChecked(false)
                .sortOrder((int) nextSortOrder)
                .build());

        writeThroughOnCreate(planId, saved);

        return new CreateChecklistItemResponse(saved.getChecklistItemId());
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public void editContent(UUID planId, Long itemId, String content) {
        PlanChecklistItem item = getItem(planId, itemId);
        item.editContent(content);
        writeThroughOnUpdate(itemId, cached -> cached.toBuilder().content(content).build());
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public void editChecked(UUID planId, Long itemId, boolean isChecked) {
        PlanChecklistItem item = getItem(planId, itemId);
        item.changeChecked(isChecked);
        writeThroughOnUpdate(itemId, cached -> cached.toBuilder().isChecked(isChecked).build());
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public void deleteItem(UUID planId, Long itemId) {
        PlanChecklistItem item = getItem(planId, itemId);
        checklistItemRepository.delete(item);
        writeThroughOnDelete(itemId);
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public void reorder(UUID planId, List<Long> itemIds) {
        for (int i = 0; i < itemIds.size(); i++) {
            Long itemId = itemIds.get(i);
            int sortOrder = i;
            PlanChecklistItem item = getItem(planId, itemId);
            item.changeSortOrder(sortOrder);
            writeThroughOnUpdate(itemId, cached -> cached.toBuilder().sortOrder(sortOrder).build());
        }
    }

    private PlanChecklistItem getItem(UUID planId, Long itemId) {
        return checklistItemRepository.findByChecklistItemIdAndPlanPlanId(itemId, planId)
                .orElseThrow(ChecklistItemNotFoundException::new);
    }

    // 새 항목은 아직 캐시에 없어 자기 자신으로 존재 여부를 체크할 수 없다.
    // 대신 부모(Plan)가 캐시에 떠 있는지로 "지금 실시간 편집 세션 중인가"를 판단한다.
    // DB가 이미 실제 id를 발급했으므로, 그 id를 그대로 채운 DTO를 캐시에 넣는다 —
    // id를 비워두면 SharedSync가 자체 ID Pool에서 새 id를 발급해 DB의 실제 id와 어긋난다.
    private void writeThroughOnCreate(UUID planId, PlanChecklistItem saved) {
        try {
            if (planCache.findById(planId).isPresent()) {
                checklistItemCache.save(new PlanChecklistItemDto(
                        saved.getChecklistItemId(), planId, saved.getContent(),
                        saved.isChecked(), saved.getSortOrder()));
            }
        } catch (Exception e) {
            log.warn("[PlanChecklist] 항목 생성 후 SharedSync 캐시 갱신 실패 (DB는 정상 반영됨). itemId={}",
                    saved.getChecklistItemId(), e);
        }
    }

    // 캐시에 떠 있는 "현재" 값 위에 이번에 바뀐 필드만 얹는다 (editPlanName과 동일한 이유 —
    // DB에서 막 읽은 값을 그대로 기준으로 삼으면, 캐시에만 반영되고 아직 DB에 동기화되지 않은
    // 다른 필드의 실시간 편집 결과를 덮어써 버릴 수 있다).
    private void writeThroughOnUpdate(Long itemId, UnaryOperator<PlanChecklistItem> applyChange) {
        try {
            checklistItemCache.findById(itemId).ifPresent(cached ->
                    checklistItemCache.save(PlanChecklistItemDto.fromEntity(applyChange.apply(cached))));
        } catch (Exception e) {
            log.warn("[PlanChecklist] 항목 수정 후 SharedSync 캐시 갱신 실패 (DB는 정상 반영됨). itemId={}", itemId, e);
        }
    }

    private void writeThroughOnDelete(Long itemId) {
        try {
            if (checklistItemCache.findById(itemId).isPresent()) {
                checklistItemCache.deleteById(itemId);
            }
        } catch (Exception e) {
            log.warn("[PlanChecklist] 항목 삭제 후 SharedSync 캐시 갱신 실패 (DB는 정상 반영됨). itemId={}", itemId, e);
        }
    }
}
