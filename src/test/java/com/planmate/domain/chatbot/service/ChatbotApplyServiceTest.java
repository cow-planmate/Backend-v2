package com.planmate.domain.chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.planmate.common.exception.domain.ChatbotApplySessionInvalidException;
import com.planmate.common.exception.domain.ChatbotApplySnapshotUnavailableException;
import com.planmate.domain.chatbot.dto.ChatbotApplyBlockDto;
import com.planmate.domain.chatbot.dto.ChatbotApplyPlanDto;
import com.planmate.domain.chatbot.dto.ChatbotApplyRequest;
import com.planmate.domain.chatbot.dto.ChatbotApplyResponse;
import com.planmate.domain.chatbot.dto.ChatbotApplyTimetableDto;
import com.planmate.domain.chatbot.reconcile.PlanReconciler;
import com.planmate.domain.chatbot.support.ChatbotBaselineStore;
import com.planmate.domain.chatbot.support.PlanContentFingerprint;
import com.planmate.domain.place.repository.PlaceDetailRepository;
import com.planmate.domain.plan.dto.PlanFrameDetailDto;
import com.planmate.domain.plan.dto.TimetableDetailDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDetailDto;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.plan.service.PlanSnapshotReader;
import com.planmate.domain.plan.service.PlanSnapshotReader.PlanSnapshot;
import com.planmate.domain.timetable.enums.BlockCategory;
import com.sharedsync.shared.history.HistoryAction;
import com.sharedsync.shared.history.HistoryService;
import com.sharedsync.shared.sync.RedisSyncService;
import com.sharedsync.shared.transport.WebSocketSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sharedsync.cache.TimeTableCache;
import sharedsync.cache.TimeTablePlaceBlockCache;
import sharedsync.dto.TimeTableDto;
import sharedsync.dto.TimeTablePlaceBlockDto;
import sharedsync.service.SharedTimeTablePlaceBlockService;
import sharedsync.service.SharedTimeTableService;
import sharedsync.wsdto.WTimeTablePlaceBlockResponse;
import sharedsync.wsdto.WTimeTableResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ChatbotApplyService} 단위테스트 — 세션 조회 분기(없음/1개/다중), 빈 diff 스킵,
 * leaf-first 적용 순서, 복합 {@link HistoryAction.Type#COMPOSITE} 조립.
 *
 * <p>{@link PlanReconciler}는 실제 구현을 그대로 쓴다(순수 함수, 의존성 없음) — reconcile 결과를
 * 손으로 흉내 내는 대신 saved/target 스냅샷을 원하는 diff가 나오도록 구성한다.
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §11.
 */
@ExtendWith(MockitoExtension.class)
class ChatbotApplyServiceTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private static final LocalDate DAY1 = LocalDate.of(2026, 10, 11);
    private static final LocalDate DAY2 = LocalDate.of(2026, 10, 12);
    private static final LocalDate DAY3 = LocalDate.of(2026, 10, 13);
    private static final LocalDate DAY4 = LocalDate.of(2026, 10, 14);
    private static final LocalTime T08 = LocalTime.of(8, 0);
    private static final LocalTime T09 = LocalTime.of(9, 0);
    private static final LocalTime T10 = LocalTime.of(10, 0);
    private static final LocalTime T20 = LocalTime.of(20, 0);
    private static final LocalTime T22 = LocalTime.of(22, 0);

    @Mock
    private WebSocketSessionRegistry webSocketSessionRegistry;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private PlanSnapshotReader planSnapshotReader;
    @Mock
    private PlaceDetailRepository placeDetailRepository;
    @Mock
    private SharedTimeTableService sharedTimeTableService;
    @Mock
    private SharedTimeTablePlaceBlockService sharedTimeTablePlaceBlockService;
    @Mock
    private TimeTableCache timeTableCache;
    @Mock
    private TimeTablePlaceBlockCache timeTablePlaceBlockCache;
    @Mock
    private HistoryService historyService;
    @Mock
    private RedisSyncService redisSyncService;
    @Mock
    private ChatbotBaselineStore chatbotBaselineStore;

    private final PlanReconciler planReconciler = new PlanReconciler();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private ChatbotApplyService service;

    @BeforeEach
    void setUp() {
        service = new ChatbotApplyService(webSocketSessionRegistry, planRepository, planSnapshotReader,
                placeDetailRepository, planReconciler, sharedTimeTableService, sharedTimeTablePlaceBlockService,
                timeTableCache, timeTablePlaceBlockCache, historyService, redisSyncService, chatbotBaselineStore,
                objectMapper);
    }

    // ---------- fixtures ----------

    private Plan plan() {
        return Plan.builder().planId(PLAN_ID).build();
    }

    private PlanFrameDetailDto frame() {
        return new PlanFrameDetailDto(PLAN_ID, "부산 여행", 20, "부산", 2, 0);
    }

    private PlanSnapshot snapshot(List<TimetableDetailDto> timetables, List<TimetablePlaceBlockDetailDto> blocks) {
        return new PlanSnapshot(frame(), timetables, blocks, PlanSnapshotReader.Source.CACHE);
    }

    private TimetableDetailDto savedTimetable(long id, LocalDate date) {
        return new TimetableDetailDto(id, date, T09, T20);
    }

    private ChatbotApplyTimetableDto targetTimetable(Long id, LocalDate date) {
        return new ChatbotApplyTimetableDto(id, date, T09, T20);
    }

    private ChatbotApplyTimetableDto targetTimetable(Long id, LocalDate date, LocalTime start, LocalTime end) {
        return new ChatbotApplyTimetableDto(id, date, start, end);
    }

    private TimetablePlaceBlockDetailDto savedBlock(long blockId, long timeTableId, String placeName) {
        return new TimetablePlaceBlockDetailDto(blockId, timeTableId, null, placeName, null, null, null, null,
                null, null, T09, T10, BlockCategory.ATTRACTION, "memo");
    }

    private ChatbotApplyBlockDto targetBlock(Long blockId, Long timeTableId, LocalDate date, String placeName) {
        return new ChatbotApplyBlockDto(blockId, timeTableId, date, null, placeName, null, null, null, null,
                null, null, T09, T10, BlockCategory.ATTRACTION, "memo");
    }

    private ChatbotApplyRequest request(List<ChatbotApplyTimetableDto> timetables,
                                         List<ChatbotApplyBlockDto> blocks) {
        return new ChatbotApplyRequest(new ChatbotApplyPlanDto(null, timetables, blocks));
    }

    private void stubSession(String... sessionIds) {
        given(webSocketSessionRegistry.sessionIdsOf(PLAN_ID.toString(), USER_ID.toString()))
                .willReturn(Set.of(sessionIds));
    }

    private void stubPlan(PlanSnapshot saved) {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan()));
        given(planSnapshotReader.read(any(Plan.class))).willReturn(saved);
    }

    // ---------- 세션 조회 분기 (설계 §9-1) ----------

    @Test
    @DisplayName("세션 없음 → ChatbotApplySessionInvalidException, 저장본 조회조차 안 감")
    void noLiveSession_throwsAndNeverReadsPlan() {
        stubSession();

        assertThatThrownBy(() -> service.apply(PLAN_ID, USER_ID, request(List.of(), List.of())))
                .isInstanceOf(ChatbotApplySessionInvalidException.class);

        verifyNoInteractions(planRepository, planSnapshotReader, historyService, redisSyncService);
    }

    @Test
    @DisplayName("세션 1개 → 정상 바인딩되어 처리가 진행된다")
    void oneLiveSession_proceeds() {
        stubSession("sess-1");
        stubPlan(snapshot(List.of(savedTimetable(1, DAY1)), List.of()));
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn(null);

        ChatbotApplyResponse response =
                service.apply(PLAN_ID, USER_ID, request(List.of(targetTimetable(1L, DAY1)), List.of()));

        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("세션 여러 개(다중 탭) → 그중 하나로 정상 처리된다 (설계 §12-1 트레이드오프)")
    void multipleSessions_picksOneAndProceeds() {
        stubSession("sess-1", "sess-2");
        stubPlan(snapshot(List.of(savedTimetable(1, DAY1)), List.of()));
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn(null);

        ChatbotApplyResponse response =
                service.apply(PLAN_ID, USER_ID, request(List.of(targetTimetable(1L, DAY1)), List.of()));

        assertThat(response).isNotNull();
    }

    // ---------- 늦은 저장본 거부 (설계 §12-2) ----------

    @Test
    @DisplayName("저장본이 캐시 실패 후 DB 폴백본 → ChatbotApplySnapshotUnavailableException, 아무것도 적용 안 함")
    void cacheFailedSnapshot_throwsAndAppliesNothing() {
        stubSession("sess-1");
        // 직전 [반영] 블록이 아직 DB 에 없는 상황 — 이대로 reconcile 하면 blockId 11 을 새로 만든다.
        stubPlan(new PlanSnapshot(frame(), List.of(savedTimetable(1, DAY1)), List.of(),
                PlanSnapshotReader.Source.CACHE_FAILED_DATABASE));

        assertThatThrownBy(() -> service.apply(PLAN_ID, USER_ID,
                request(List.of(targetTimetable(1L, DAY1)), List.of(targetBlock(11L, 1L, DAY1, "경복궁")))))
                .isInstanceOf(ChatbotApplySnapshotUnavailableException.class);

        verifyNoInteractions(chatbotBaselineStore, historyService, redisSyncService, sharedTimeTableService,
                sharedTimeTablePlaceBlockService, timeTableCache, timeTablePlaceBlockCache);
    }

    @Test
    @DisplayName("캐시에 플랜이 없어 DB 에서 읽은 저장본은 최신이라 정상 처리한다")
    void databaseSnapshot_proceeds() {
        stubSession("sess-1");
        stubPlan(new PlanSnapshot(frame(), List.of(savedTimetable(1, DAY1)), List.of(),
                PlanSnapshotReader.Source.DATABASE));
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn(null);

        ChatbotApplyResponse response =
                service.apply(PLAN_ID, USER_ID, request(List.of(targetTimetable(1L, DAY1)), List.of()));

        assertThat(response).isNotNull();
    }

    // ---------- 충돌 감지 (설계 §10-1-1) ----------

    @Test
    @DisplayName("baseline 없음(TTL 만료 등) → conflictDetected=true")
    void noBaseline_conflictDetected() {
        stubSession("sess-1");
        stubPlan(snapshot(List.of(savedTimetable(1, DAY1)), List.of()));
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn(null);

        ChatbotApplyResponse response =
                service.apply(PLAN_ID, USER_ID, request(List.of(targetTimetable(1L, DAY1)), List.of()));

        assertThat(response.conflictDetected()).isTrue();
    }

    @Test
    @DisplayName("baseline 이 현재 저장본과 같은 지문 → conflictDetected=false")
    void baselineMatchesCurrent_noConflict() {
        stubSession("sess-1");
        PlanSnapshot saved = snapshot(List.of(savedTimetable(1, DAY1)), List.of());
        stubPlan(saved);
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn(PlanContentFingerprint.of(objectMapper, saved));

        ChatbotApplyResponse response =
                service.apply(PLAN_ID, USER_ID, request(List.of(targetTimetable(1L, DAY1)), List.of()));

        assertThat(response.conflictDetected()).isFalse();
    }

    // ---------- 빈 diff (설계 §5-6) ----------

    @Test
    @DisplayName("빈 diff(중복 제출 등) — 적용/COMPOSITE 기록/브로드캐스트 전부 스킵, conflictDetected 는 정상 계산")
    void emptyDiff_skipsApplyStepsButStillComputesConflict() {
        stubSession("sess-1");
        stubPlan(snapshot(List.of(savedTimetable(1, DAY1)), List.of(savedBlock(11, 1, "경복궁"))));
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn(null);

        ChatbotApplyResponse response = service.apply(PLAN_ID, USER_ID,
                request(List.of(targetTimetable(1L, DAY1)), List.of(targetBlock(11L, 1L, DAY1, "경복궁"))));

        assertThat(response.conflictDetected()).isTrue(); // baseline null

        verifyNoInteractions(historyService, redisSyncService, sharedTimeTableService,
                sharedTimeTablePlaceBlockService, timeTableCache, timeTablePlaceBlockCache);
    }

    // ---------- 실 적용 + COMPOSITE 조립 (단일 연산) ----------

    @Test
    @DisplayName("블록 하나 변경 → reconcile 적용 + COMPOSITE(subAction 1개) 기록 + 브로드캐스트 1회")
    void singleBlockUpdate_appliesAndRecordsComposite() {
        stubSession("sess-1");
        stubPlan(snapshot(List.of(savedTimetable(1, DAY1)), List.of(savedBlock(11, 1, "경복궁"))));
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn("irrelevant-baseline");

        given(timeTablePlaceBlockCache.findDtoById(11L)).willReturn(
                new TimeTablePlaceBlockDto(11L, 1L, null, "경복궁", null, null, null, null, null, null,
                        T09, T10, BlockCategory.ATTRACTION, "memo"));
        WTimeTablePlaceBlockResponse updateResponse = new WTimeTablePlaceBlockResponse();
        updateResponse.setTimeTablePlaceBlockDtos(List.of(
                new TimeTablePlaceBlockDto(11L, 1L, null, "창덕궁", null, null, null, null, null, null,
                        T09, T10, BlockCategory.ATTRACTION, "memo")));
        given(sharedTimeTablePlaceBlockService.update(any())).willReturn(updateResponse);

        ChatbotApplyResponse response = service.apply(PLAN_ID, USER_ID,
                request(List.of(targetTimetable(1L, DAY1)), List.of(targetBlock(11L, 1L, DAY1, "창덕궁"))));

        assertThat(response).isNotNull();

        ArgumentCaptor<HistoryAction> captor = ArgumentCaptor.forClass(HistoryAction.class);
        verify(historyService).record(eq(PLAN_ID.toString()), captor.capture());
        HistoryAction composite = captor.getValue();
        assertThat(composite.getType()).isEqualTo(HistoryAction.Type.COMPOSITE);
        assertThat(composite.getSubActions()).hasSize(1);
        assertThat(composite.getSubActions().get(0).getType()).isEqualTo(HistoryAction.Type.UPDATE);
        assertThat(composite.getSubActions().get(0).getEntityName()).isEqualTo("timetableplaceblock");

        verify(redisSyncService, times(1)).publish(eq("/topic/" + PLAN_ID), any());
    }

    // ---------- leaf-first 적용 순서 + 복합 COMPOSITE (설계 §5-4) ----------

    @Test
    @DisplayName("leaf-first 순서 — createTT→createBlock→updateTT→updateBlock→deleteBlock→deleteTT, "
            + "COMPOSITE 에 6개 subAction")
    void applyDiff_leafFirstOrderAndFullCompositeAssembly() {
        stubSession("sess-1");

        PlanSnapshot saved = snapshot(
                List.of(savedTimetable(1, DAY1), savedTimetable(2, DAY2), savedTimetable(3, DAY3)),
                List.of(savedBlock(11, 1, "경복궁"), savedBlock(21, 2, "부산타워"), savedBlock(31, 3, "곧삭제됨")));
        stubPlan(saved);
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn(null);

        // before 스냅샷(update/delete 조회 대상)
        given(timeTableCache.findDtoById(2L)).willReturn(new TimeTableDto(2L, DAY2, T09, T20, PLAN_ID));
        given(timeTableCache.findDtoById(3L)).willReturn(new TimeTableDto(3L, DAY3, T09, T20, PLAN_ID));
        given(timeTablePlaceBlockCache.findDtoById(21L)).willReturn(
                new TimeTablePlaceBlockDto(21L, 2L, null, "부산타워", null, null, null, null, null, null,
                        T09, T10, BlockCategory.ATTRACTION, "memo"));
        given(timeTablePlaceBlockCache.findDtoById(31L)).willReturn(
                new TimeTablePlaceBlockDto(31L, 3L, null, "곧삭제됨", null, null, null, null, null, null,
                        T09, T10, BlockCategory.ATTRACTION, "memo"));

        // create 응답
        WTimeTableResponse createTtResponse = new WTimeTableResponse();
        createTtResponse.setTimeTableDtos(List.of(new TimeTableDto(100L, DAY4, T09, T20, PLAN_ID)));
        given(sharedTimeTableService.create(any())).willReturn(createTtResponse);

        WTimeTablePlaceBlockResponse createBlockResponse = new WTimeTablePlaceBlockResponse();
        createBlockResponse.setTimeTablePlaceBlockDtos(List.of(
                new TimeTablePlaceBlockDto(200L, 100L, null, "새로운장소", null, null, null, null, null, null,
                        T09, T10, BlockCategory.ATTRACTION, "memo")));
        given(sharedTimeTablePlaceBlockService.create(any())).willReturn(createBlockResponse);

        // update 응답
        WTimeTableResponse updateTtResponse = new WTimeTableResponse();
        updateTtResponse.setTimeTableDtos(List.of(new TimeTableDto(2L, DAY2, T08, T22, PLAN_ID)));
        given(sharedTimeTableService.update(any())).willReturn(updateTtResponse);

        WTimeTablePlaceBlockResponse updateBlockResponse = new WTimeTablePlaceBlockResponse();
        updateBlockResponse.setTimeTablePlaceBlockDtos(List.of(
                new TimeTablePlaceBlockDto(21L, 2L, null, "송정해변", null, null, null, null, null, null,
                        T09, T10, BlockCategory.ATTRACTION, "memo")));
        given(sharedTimeTablePlaceBlockService.update(any())).willReturn(updateBlockResponse);

        // delete 응답
        given(sharedTimeTablePlaceBlockService.delete(any())).willReturn(new WTimeTablePlaceBlockResponse());
        given(sharedTimeTableService.delete(any())).willReturn(new WTimeTableResponse());

        ChatbotApplyRequest req = request(
                List.of(
                        targetTimetable(1L, DAY1),                       // 그대로 — skip
                        targetTimetable(2L, DAY2, T08, T22),             // 창 확장 — UPDATE
                        targetTimetable(null, DAY4)                      // 새 날 — CREATE
                        // tt3(DAY3) 는 target 에 없음 — DELETE
                ),
                List.of(
                        targetBlock(11L, 1L, DAY1, "경복궁"),             // 그대로 — skip
                        targetBlock(21L, 2L, DAY2, "송정해변"),           // 장소 교체 — UPDATE
                        targetBlock(null, null, DAY4, "새로운장소")        // 새 블록(새 날) — CREATE
                        // block31 은 target 에 없음 — DELETE
                ));

        ChatbotApplyResponse response = service.apply(PLAN_ID, USER_ID, req);

        assertThat(response).isNotNull();

        InOrder inOrder = inOrder(sharedTimeTableService, sharedTimeTablePlaceBlockService);
        inOrder.verify(sharedTimeTableService).create(any());
        inOrder.verify(sharedTimeTablePlaceBlockService).create(any());
        inOrder.verify(sharedTimeTableService).update(any());
        inOrder.verify(sharedTimeTablePlaceBlockService).update(any());
        inOrder.verify(sharedTimeTablePlaceBlockService).delete(any());
        inOrder.verify(sharedTimeTableService).delete(any());

        ArgumentCaptor<HistoryAction> captor = ArgumentCaptor.forClass(HistoryAction.class);
        verify(historyService).record(eq(PLAN_ID.toString()), captor.capture());
        HistoryAction composite = captor.getValue();

        assertThat(composite.getType()).isEqualTo(HistoryAction.Type.COMPOSITE);
        assertThat(composite.getSubActions()).hasSize(6);
        assertThat(composite.getSubActions()).extracting(HistoryAction::getType)
                .containsExactly(
                        HistoryAction.Type.CREATE, HistoryAction.Type.CREATE,
                        HistoryAction.Type.UPDATE, HistoryAction.Type.UPDATE,
                        HistoryAction.Type.DELETE, HistoryAction.Type.DELETE);

        // 하나의 [반영] = 하나의 브로드캐스트 이벤트로 묶인다(client.jsx 의 isDifferentEventId 필터 대응, §4 단계4 주석).
        List<String> eventIds = composite.getSubActions().stream().map(HistoryAction::getEventId).distinct().toList();
        assertThat(eventIds).hasSize(1);
        assertThat(eventIds.get(0)).isNotBlank();

        verify(redisSyncService, times(6)).publish(eq("/topic/" + PLAN_ID), any());
    }

    // ---------- 캐시 열화 내성 (10_E2E…기록.md 발견 A1 회귀) ----------

    @Test
    @DisplayName("삭제 블록의 before 스냅샷이 캐시 미스로 null — NPE 없이 삭제·COMPOSITE 기록·브로드캐스트 정상 (Fix 3a)")
    void deleteBlock_beforeSnapshotCacheMiss_noNpe_stillAppliesAndRecords() {
        stubSession("sess-1");
        stubPlan(snapshot(
                List.of(savedTimetable(1, DAY1)),
                List.of(savedBlock(11, 1, "경복궁"), savedBlock(31, 1, "곧삭제됨"))));
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn(null);

        // ⚠️ 캐시 열화 재현 — 삭제 대상 블록의 findDtoById 가 null 을 돌려준다.
        given(timeTablePlaceBlockCache.findDtoById(31L)).willReturn(null);
        given(sharedTimeTablePlaceBlockService.delete(any())).willReturn(new WTimeTablePlaceBlockResponse());

        ChatbotApplyResponse response = service.apply(PLAN_ID, USER_ID,
                request(List.of(targetTimetable(1L, DAY1)), List.of(targetBlock(11L, 1L, DAY1, "경복궁"))));

        // 예외로 죽지 않고 200 성공
        assertThat(response).isNotNull();

        // 삭제는 그대로 실행
        verify(sharedTimeTablePlaceBlockService).delete(any());

        // undo 도 기록됨 (record 에 도달) — before 에 null 은 안 들어감
        ArgumentCaptor<HistoryAction> captor = ArgumentCaptor.forClass(HistoryAction.class);
        verify(historyService).record(eq(PLAN_ID.toString()), captor.capture());
        HistoryAction composite = captor.getValue();
        assertThat(composite.getType()).isEqualTo(HistoryAction.Type.COMPOSITE);
        assertThat(composite.getSubActions()).hasSize(1);
        HistoryAction deleteAction = composite.getSubActions().get(0);
        assertThat(deleteAction.getType()).isEqualTo(HistoryAction.Type.DELETE);
        assertThat(deleteAction.getBeforeData()).isEmpty(); // null 원소가 걸러져 빈 리스트

        // 브로드캐스트도 정상 시도 (빈 리스트라도 발행은 함)
        verify(redisSyncService, times(1)).publish(eq("/topic/" + PLAN_ID), any());
    }

    @Test
    @DisplayName("브로드캐스트(publish)가 예외를 던져도 — [반영] 은 성공, COMPOSITE 는 기록됨 (Fix 1)")
    void broadcastThrows_stillSucceedsAndRecordsComposite() {
        stubSession("sess-1");
        stubPlan(snapshot(List.of(savedTimetable(1, DAY1)), List.of(savedBlock(11, 1, "경복궁"))));
        given(chatbotBaselineStore.find(PLAN_ID, USER_ID)).willReturn("irrelevant-baseline");

        given(timeTablePlaceBlockCache.findDtoById(11L)).willReturn(
                new TimeTablePlaceBlockDto(11L, 1L, null, "경복궁", null, null, null, null, null, null,
                        T09, T10, BlockCategory.ATTRACTION, "memo"));
        WTimeTablePlaceBlockResponse updateResponse = new WTimeTablePlaceBlockResponse();
        updateResponse.setTimeTablePlaceBlockDtos(List.of(
                new TimeTablePlaceBlockDto(11L, 1L, null, "창덕궁", null, null, null, null, null, null,
                        T09, T10, BlockCategory.ATTRACTION, "memo")));
        given(sharedTimeTablePlaceBlockService.update(any())).willReturn(updateResponse);

        // ⚠️ Redis 발행이 터진다 (ProtoSyncCodec NPE, Redis 다운 등)
        willThrow(new RuntimeException("codec/redis 실패")).given(redisSyncService).publish(any(), any());

        ChatbotApplyResponse response = service.apply(PLAN_ID, USER_ID,
                request(List.of(targetTimetable(1L, DAY1)), List.of(targetBlock(11L, 1L, DAY1, "창덕궁"))));

        // 브로드캐스트 실패는 무시 — [반영] 자체는 성공
        assertThat(response).isNotNull();

        // 적용도 undo 기록도 정상 (broadcast 예외가 흐름을 끊지 않음)
        verify(sharedTimeTablePlaceBlockService).update(any());
        verify(historyService).record(eq(PLAN_ID.toString()), any(HistoryAction.class));
    }
}
