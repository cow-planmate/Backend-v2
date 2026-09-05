package com.planmate.domain.chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.exception.domain.ChatbotApplySessionInvalidException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.domain.chatbot.dto.ChatbotApplyBlockDto;
import com.planmate.domain.chatbot.dto.ChatbotApplyPlanDto;
import com.planmate.domain.chatbot.dto.ChatbotApplyRequest;
import com.planmate.domain.chatbot.dto.ChatbotApplyResponse;
import com.planmate.domain.chatbot.reconcile.PlanReconciler;
import com.planmate.domain.chatbot.reconcile.PlanReconciler.BlockCreateOp;
import com.planmate.domain.chatbot.reconcile.PlanReconciler.BlockUpdateOp;
import com.planmate.domain.chatbot.reconcile.PlanReconciler.ReconcileResult;
import com.planmate.domain.chatbot.support.ChatbotBaselineStore;
import com.planmate.domain.chatbot.support.PlanContentFingerprint;
import com.planmate.domain.place.repository.PlaceDetailRepository;
import com.planmate.domain.place.repository.PlaceDetailRepository.BaseRow;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.plan.service.PlanSnapshotReader;
import com.planmate.domain.plan.service.PlanSnapshotReader.PlanSnapshot;
import com.sharedsync.shared.codec.SyncOutbound;
import com.sharedsync.shared.history.HistoryAction;
import com.sharedsync.shared.history.HistoryService;
import com.sharedsync.shared.sync.RedisSyncService;
import com.sharedsync.shared.transport.WebSocketSessionRegistry;
import com.sharedsync.shared.transport.WebSocketSyncSessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import sharedsync.cache.TimeTableCache;
import sharedsync.cache.TimeTablePlaceBlockCache;
import sharedsync.dto.TimeTableDto;
import sharedsync.dto.TimeTablePlaceBlockDto;
import sharedsync.service.SharedTimeTablePlaceBlockService;
import sharedsync.service.SharedTimeTableService;
import sharedsync.wsdto.WTimeTablePlaceBlockRequest;
import sharedsync.wsdto.WTimeTableRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 챗봇 [반영](④-2) — 프론트 working copy 를 저장본과 id 로 reconcile 해 SharedSync 에 반영한다.
 *
 * <p>단계 0~6 전체가 반드시 동기 실행이어야 한다 — {@link WebSocketSyncSessionContext}가
 * ThreadLocal 이라 스레드가 넘어가면(예: {@code @Async}) undo 히스토리가 조용히 깨진다(설계 §4).
 *
 * <p>{@code @Transactional} 을 붙이지 않는다 — 이 메서드가 만지는 건 tourdata JDBC(트랜잭션
 * 매니저 없음, ②③ 선례)와 SharedSync Redis 캐시(앱 DB 트랜잭션과 무관)뿐, 앱 DB에 직접 쓰지
 * 않는다(설계 §4 단계4, `06` §7 ④-1과 동일 원칙).
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatbotApplyService {

    private final WebSocketSessionRegistry webSocketSessionRegistry;
    private final PlanRepository planRepository;
    private final PlanSnapshotReader planSnapshotReader;
    private final PlaceDetailRepository placeDetailRepository;
    private final PlanReconciler planReconciler;
    private final SharedTimeTableService sharedTimeTableService;
    private final SharedTimeTablePlaceBlockService sharedTimeTablePlaceBlockService;
    private final TimeTableCache timeTableCache;
    private final TimeTablePlaceBlockCache timeTablePlaceBlockCache;
    private final HistoryService historyService;
    private final RedisSyncService redisSyncService;
    private final ChatbotBaselineStore chatbotBaselineStore;
    private final ObjectMapper objectMapper;

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    public ChatbotApplyResponse apply(UUID planId, UUID userId, ChatbotApplyRequest request) {
        String sessionId = findLiveSession(planId, userId);

        WebSocketSyncSessionContext.bind(sessionId);
        try {
            return doApply(planId, userId, request);
        } finally {
            WebSocketSyncSessionContext.clear();
        }
    }

    // ---------- 0-② 세션 조회 (설계 §9-1) ----------

    /**
     * 이 유저가 이 방에 지금 열어둔 raw-WS 세션을 신원 기준으로 찾는다 — 클라가 자기 sessionId를
     * 몰라서(wire 프로토콜에 그걸 알려주는 수단이 없음) 안 보내므로, v2가 직접 조회한다.
     *
     * <p>같은 유저가 같은 방을 여러 세션(탭/기기)으로 열어두면 여러 개가 나올 수 있다 — 그중
     * 하나를 임의로 쓴다(undo 귀속이 그 탭이 아닐 수 있음, 심각도 낮음). 트레이드오프는 설계 §12-1.
     */
    private String findLiveSession(UUID planId, UUID userId) {
        Set<String> sessionIds = webSocketSessionRegistry.sessionIdsOf(planId.toString(), userId.toString());
        if (sessionIds.isEmpty()) {
            throw new ChatbotApplySessionInvalidException();
        }
        return sessionIds.iterator().next();
    }

    private ChatbotApplyResponse doApply(UUID planId, UUID userId, ChatbotApplyRequest request) {
        // 1. 정규화 — place 필드는 클라 값을 믿지 않고 tourdata 에서 재조회(설계 §4 단계1)
        List<ChatbotApplyBlockDto> rehydratedBlocks = rehydratePlaceFields(request.plan().placeBlocks());
        ChatbotApplyPlanDto target =
                new ChatbotApplyPlanDto(request.plan().planFrame(), request.plan().timetables(), rehydratedBlocks);

        // 2. 저장본
        Plan plan = planRepository.findById(planId).orElseThrow(PlanNotFoundException::new);
        PlanSnapshot saved = planSnapshotReader.read(plan);

        boolean conflictDetected = detectConflict(planId, userId, saved);

        // 3. reconcile diff
        ReconcileResult diff = planReconciler.reconcile(saved, target);
        if (diff.isEmpty()) {
            // 중복 제출 등으로 target == saved. 빈 COMPOSITE 를 undo 스택에 안 쌓는다(설계 §5-6).
            return new ChatbotApplyResponse(true, conflictDetected);
        }

        // 4~6. 적용 + 복합 HistoryAction + 브로드캐스트
        applyDiff(planId, saved, diff);

        return new ChatbotApplyResponse(true, conflictDetected);
    }

    private boolean detectConflict(UUID planId, UUID userId, PlanSnapshot saved) {
        String currentFingerprint = PlanContentFingerprint.of(objectMapper, saved);
        String baseline = chatbotBaselineStore.find(planId, userId);
        return baseline == null || !baseline.equals(currentFingerprint);
    }

    // ---------- 1. place 필드 재hydrate ----------

    private List<ChatbotApplyBlockDto> rehydratePlaceFields(List<ChatbotApplyBlockDto> blocks) {
        List<ChatbotApplyBlockDto> result = new ArrayList<>(blocks.size());
        for (ChatbotApplyBlockDto b : blocks) {
            if (b.placeId() == null || b.placeId().isBlank()) {
                result.add(b);
                continue;
            }
            BaseRow base = placeDetailRepository.fetchBase(b.placeId());
            if (base == null) {
                // tourdata 에 없는 장소(카카오/구글 검색 결과 등) — 클라 값 신뢰(설계 §4 단계1)
                result.add(b);
                continue;
            }
            result.add(new ChatbotApplyBlockDto(
                    b.blockId(), b.timeTableId(), b.date(), b.placeId(),
                    base.title(), base.contentTypeId(), base.addr1(), base.thumbnail(), base.copyrightDivCd(),
                    toBigDecimal(base.mapy()), toBigDecimal(base.mapx()),
                    b.blockStartTime(), b.blockEndTime(), b.blockCategory(), b.memo()));
        }
        return result;
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    // ---------- 4~6. 적용(leaf-first) + 복합 HistoryAction + 브로드캐스트 ----------

    private void applyDiff(UUID planId, PlanSnapshot saved, ReconcileResult diff) {
        // "" 를 쓰면 client.jsx 의 isDifferentEventId 필터가 update/delete 블록 브로드캐스트를
        // 모든 클라이언트(요청자 포함)에서 조용히 무시한다 — 접속 중인 클라는 페이지 진입 시
        // usePlanStore.setEventId() 로 고유 UUID 를 이미 갖고 있어서, 그 값과 절대 겹치지 않는
        // 새 UUID 를 매번 발급해야 "다른 사람의 편집"으로 인식돼 반영된다.
        String broadcastEventId = UUID.randomUUID().toString();
        List<HistoryAction> subActions = new ArrayList<>();

        Map<LocalDate, Long> dateToTimeTableId = new HashMap<>();
        saved.timetables().forEach(t -> dateToTimeTableId.put(t.date(), t.timeTableId()));

        HistoryService.setSkipHistory(true);
        try {
            applyCreateTimeTables(planId, diff, dateToTimeTableId, subActions, broadcastEventId);
            applyCreateBlocks(planId, diff, dateToTimeTableId, subActions, broadcastEventId);
            applyUpdateTimeTables(planId, diff, subActions, broadcastEventId);
            applyUpdateBlocks(planId, diff, dateToTimeTableId, subActions, broadcastEventId);
            // 삭제는 블록 먼저 — AutoCacheRepository.deleteCacheCascade 가 부모 삭제 시 자식을
            // setSkipHistory 와 무관하게 조용히 cascade 지우기 때문에, 반대 순서면 그 뒤 블록
            // 삭제의 before 스냅샷이 이미 null 이 되어 undo 가 깨진다(설계 §5-4, 필수).
            applyDeleteBlocks(planId, diff, subActions, broadcastEventId);
            applyDeleteTimeTables(planId, diff, subActions, broadcastEventId);

            HistoryAction composite = HistoryAction.builder()
                    .type(HistoryAction.Type.COMPOSITE)
                    .subActions(subActions)
                    .eventId(broadcastEventId)
                    .timestamp(System.currentTimeMillis())
                    .build();
            historyService.record(planId.toString(), composite);
        } finally {
            HistoryService.setSkipHistory(false);
        }
    }

    private void applyCreateTimeTables(UUID planId, ReconcileResult diff, Map<LocalDate, Long> dateToTimeTableId,
                                        List<HistoryAction> subActions, String eventId) {
        if (diff.createTimeTables().isEmpty()) {
            return;
        }
        List<TimeTableDto> toCreate = diff.createTimeTables().stream()
                .map(op -> new TimeTableDto(null, op.date(), op.timeTableStartTime(), op.timeTableEndTime(), planId))
                .toList();
        List<TimeTableDto> created = sharedTimeTableService.create(timeTableRequest(planId, toCreate))
                .getTimeTableDtos();
        // AutoCacheRepository.saveAll 은 입력 순서를 그대로 유지해서 반환한다(id 만 채워 넣음) —
        // TimeTableDto 에 getDate() 가 없어 응답에서 직접 못 읽으므로, 우리가 이미 아는 순서로 짝짓는다.
        for (int i = 0; i < diff.createTimeTables().size(); i++) {
            dateToTimeTableId.put(diff.createTimeTables().get(i).date(), created.get(i).getTimeTableId());
        }
        subActions.add(timeTableHistoryAction(HistoryAction.Type.CREATE, null, created, eventId));
        broadcast(planId, eventId, "timetable", "create", "timeTableDtos", created);
    }

    private void applyCreateBlocks(UUID planId, ReconcileResult diff, Map<LocalDate, Long> dateToTimeTableId,
                                    List<HistoryAction> subActions, String eventId) {
        if (diff.createBlocks().isEmpty()) {
            return;
        }
        List<TimeTablePlaceBlockDto> toCreate = diff.createBlocks().stream()
                .map(op -> createDto(resolveTimeTableId(op.timeTableId(), op.date(), dateToTimeTableId), op))
                .toList();
        List<TimeTablePlaceBlockDto> created =
                sharedTimeTablePlaceBlockService.create(blockRequest(planId, toCreate)).getTimeTablePlaceBlockDtos();
        subActions.add(blockHistoryAction(HistoryAction.Type.CREATE, null, created, eventId));
        broadcast(planId, eventId, "timetableplaceblock", "create", "timeTablePlaceBlockDtos", created);
    }

    private void applyUpdateTimeTables(UUID planId, ReconcileResult diff, List<HistoryAction> subActions,
                                        String eventId) {
        if (diff.updateTimeTables().isEmpty()) {
            return;
        }
        List<TimeTableDto> before = diff.updateTimeTables().stream()
                .map(op -> timeTableCache.findDtoById(op.timeTableId()))
                .toList();
        List<TimeTableDto> toUpdate = diff.updateTimeTables().stream()
                .map(op -> new TimeTableDto(op.timeTableId(), op.date(), op.timeTableStartTime(),
                        op.timeTableEndTime(), planId))
                .toList();
        List<TimeTableDto> updated = sharedTimeTableService.update(timeTableRequest(planId, toUpdate))
                .getTimeTableDtos();
        subActions.add(timeTableHistoryAction(HistoryAction.Type.UPDATE, before, updated, eventId));
        broadcast(planId, eventId, "timetable", "update", "timeTableDtos", updated);
    }

    private void applyUpdateBlocks(UUID planId, ReconcileResult diff, Map<LocalDate, Long> dateToTimeTableId,
                                    List<HistoryAction> subActions, String eventId) {
        if (diff.updateBlocks().isEmpty()) {
            return;
        }
        List<TimeTablePlaceBlockDto> before = diff.updateBlocks().stream()
                .map(op -> timeTablePlaceBlockCache.findDtoById(op.blockId()))
                .toList();
        List<TimeTablePlaceBlockDto> toUpdate = diff.updateBlocks().stream()
                .map(op -> updateDto(resolveTimeTableId(op.timeTableId(), op.date(), dateToTimeTableId), op))
                .toList();
        List<TimeTablePlaceBlockDto> updated =
                sharedTimeTablePlaceBlockService.update(blockRequest(planId, toUpdate)).getTimeTablePlaceBlockDtos();
        subActions.add(blockHistoryAction(HistoryAction.Type.UPDATE, before, updated, eventId));
        broadcast(planId, eventId, "timetableplaceblock", "update", "timeTablePlaceBlockDtos", updated);
    }

    private void applyDeleteBlocks(UUID planId, ReconcileResult diff, List<HistoryAction> subActions,
                                    String eventId) {
        if (diff.deleteBlockIds().isEmpty()) {
            return;
        }
        // 캐시가 열화된 상태(공유 트랜잭션 오염 등)에서 findDtoById 가 null 을 반환할 수 있다.
        // null 원소가 그대로 broadcast·HistoryAction 으로 흘러가면 ProtoSyncCodec 이 NPE 를 던져
        // [반영] 전체가 500 + 부분 적용 + undo 미기록으로 깨진다(실측). null 은 걸러내고 경고만 남긴다.
        List<TimeTablePlaceBlockDto> before = diff.deleteBlockIds().stream()
                .map(timeTablePlaceBlockCache::findDtoById)
                .filter(Objects::nonNull)
                .toList();
        if (before.size() < diff.deleteBlockIds().size()) {
            log.warn("[chatbot-apply] 삭제 블록 before 스냅샷 일부 유실(캐시 미스) planId={} 요청={} 확보={} "
                    + "— 삭제는 진행하되 해당 블록의 브로드캐스트·undo 복원은 누락됨", planId,
                    diff.deleteBlockIds().size(), before.size());
        }
        sharedTimeTablePlaceBlockService.delete(blockDeleteRequest(planId, diff.deleteBlockIds()));
        subActions.add(blockHistoryAction(HistoryAction.Type.DELETE, before, null, eventId));
        broadcast(planId, eventId, "timetableplaceblock", "delete", "timeTablePlaceBlockDtos", before);
    }

    private void applyDeleteTimeTables(UUID planId, ReconcileResult diff, List<HistoryAction> subActions,
                                        String eventId) {
        if (diff.deleteTimeTableIds().isEmpty()) {
            return;
        }
        // applyDeleteBlocks 와 동일 — 캐시 미스 시 null 원소가 코덱 NPE 를 유발하므로 걸러낸다.
        List<TimeTableDto> before = diff.deleteTimeTableIds().stream()
                .map(timeTableCache::findDtoById)
                .filter(Objects::nonNull)
                .toList();
        if (before.size() < diff.deleteTimeTableIds().size()) {
            log.warn("[chatbot-apply] 삭제 타임테이블 before 스냅샷 일부 유실(캐시 미스) planId={} 요청={} 확보={}",
                    planId, diff.deleteTimeTableIds().size(), before.size());
        }
        sharedTimeTableService.delete(timeTableDeleteRequest(planId, diff.deleteTimeTableIds()));
        subActions.add(timeTableHistoryAction(HistoryAction.Type.DELETE, before, null, eventId));
        broadcast(planId, eventId, "timetable", "delete", "timeTableDtos", before);
    }

    private Long resolveTimeTableId(Long given, LocalDate date, Map<LocalDate, Long> dateToTimeTableId) {
        return given != null ? given : dateToTimeTableId.get(date);
    }

    private TimeTablePlaceBlockDto createDto(Long timeTableId, BlockCreateOp op) {
        return new TimeTablePlaceBlockDto(null, timeTableId, op.placeId(), op.placeName(), op.placeContentTypeId(),
                op.placeAddress(), op.placeThumbnailUrl(), op.placeCopyrightDivCd(), op.latitude(), op.longitude(),
                op.blockStartTime(), op.blockEndTime(), op.blockCategory(), op.memo());
    }

    private TimeTablePlaceBlockDto updateDto(Long timeTableId, BlockUpdateOp op) {
        return new TimeTablePlaceBlockDto(op.blockId(), timeTableId, op.placeId(), op.placeName(),
                op.placeContentTypeId(), op.placeAddress(), op.placeThumbnailUrl(), op.placeCopyrightDivCd(),
                op.latitude(), op.longitude(), op.blockStartTime(), op.blockEndTime(), op.blockCategory(), op.memo());
    }

    // ---------- 요청 조립 ----------

    private WTimeTableRequest timeTableRequest(UUID planId, List<TimeTableDto> dtos) {
        WTimeTableRequest req = new WTimeTableRequest();
        req.setRootId(planId.toString());
        req.setEntity("timetable");
        req.setEventId("");
        req.setTimeTableDtos(dtos);
        return req;
    }

    private WTimeTableRequest timeTableDeleteRequest(UUID planId, List<Long> ids) {
        return timeTableRequest(planId, ids.stream()
                .map(id -> new TimeTableDto(id, null, null, null, planId))
                .toList());
    }

    private WTimeTablePlaceBlockRequest blockRequest(UUID planId, List<TimeTablePlaceBlockDto> dtos) {
        WTimeTablePlaceBlockRequest req = new WTimeTablePlaceBlockRequest();
        req.setRootId(planId.toString());
        req.setEntity("timetableplaceblock");
        req.setEventId("");
        req.setTimeTablePlaceBlockDtos(dtos);
        return req;
    }

    private WTimeTablePlaceBlockRequest blockDeleteRequest(UUID planId, List<Long> ids) {
        return blockRequest(planId, ids.stream()
                .map(id -> new TimeTablePlaceBlockDto(id, null, null, null, null, null, null, null, null, null,
                        null, null, null, null))
                .toList());
    }

    // ---------- HistoryAction 조립 ----------

    private HistoryAction timeTableHistoryAction(HistoryAction.Type type, List<TimeTableDto> before,
                                                  List<TimeTableDto> after, String eventId) {
        return HistoryAction.builder()
                .type(type)
                .entityName("timetable")
                .dtoClassName(TimeTableDto.class.getName())
                .beforeData(before)
                .afterData(after)
                .eventId(eventId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private HistoryAction blockHistoryAction(HistoryAction.Type type, List<TimeTablePlaceBlockDto> before,
                                              List<TimeTablePlaceBlockDto> after, String eventId) {
        return HistoryAction.builder()
                .type(type)
                .entityName("timetableplaceblock")
                .dtoClassName(TimeTablePlaceBlockDto.class.getName())
                .beforeData(before)
                .afterData(after)
                .eventId(eventId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ---------- 브로드캐스트 (설계 §4 단계6) ----------

    private void broadcast(UUID planId, String eventId, String entity, String action, String dtoFieldName,
                            List<?> dtos) {
        // 브로드캐스트(협업자 실시간 알림) 실패가 [반영] 자체를 실패시키면 안 된다 — reconcile·적용은
        // 이미 성공했고 undo 도 기록됐다. 협업자는 다음 동기화에서 따라잡는다. best-effort (설계 §10-2).
        try {
            redisSyncService.publish("/topic/" + planId,
                    new SyncOutbound.Entities(eventId, action, entity, false, dtoFieldName, dtos));
        } catch (RuntimeException e) {
            log.warn("[chatbot-apply] 브로드캐스트 실패 planId={} entity={} action={} — 무시하고 계속",
                    planId, entity, action, e);
        }
    }
}
