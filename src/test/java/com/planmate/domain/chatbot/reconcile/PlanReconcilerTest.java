package com.planmate.domain.chatbot.reconcile;

import com.planmate.domain.chatbot.dto.ChatbotApplyBlockDto;
import com.planmate.domain.chatbot.dto.ChatbotApplyPlanDto;
import com.planmate.domain.chatbot.dto.ChatbotApplyTimetableDto;
import com.planmate.domain.plan.dto.TimetableDetailDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDetailDto;
import com.planmate.domain.plan.service.PlanSnapshotReader.PlanSnapshot;
import com.planmate.domain.timetable.enums.BlockCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순수 함수 단위 테스트 — 목 불필요. 설계 §5·§11.
 */
class PlanReconcilerTest {

    private final PlanReconciler reconciler = new PlanReconciler();

    private static final LocalDate DAY1 = LocalDate.of(2026, 10, 11);
    private static final LocalDate DAY2 = LocalDate.of(2026, 10, 12);
    private static final LocalTime T09 = LocalTime.of(9, 0);
    private static final LocalTime T10 = LocalTime.of(10, 0);
    private static final LocalTime T20 = LocalTime.of(20, 0);

    private TimetableDetailDto timetable(long id, LocalDate date) {
        return new TimetableDetailDto(id, date, T09, T20);
    }

    private ChatbotApplyTimetableDto targetTimetable(Long id, LocalDate date) {
        return new ChatbotApplyTimetableDto(id, date, T09, T20);
    }

    private TimetablePlaceBlockDetailDto block(long blockId, long timeTableId, String placeName) {
        return new TimetablePlaceBlockDetailDto(blockId, timeTableId, "P1", placeName, "12", "addr",
                "thumb", "Type3", BigDecimal.valueOf(35.1), BigDecimal.valueOf(129.0), T09, T10,
                BlockCategory.ATTRACTION, "memo");
    }

    private ChatbotApplyBlockDto targetBlock(Long blockId, Long timeTableId, LocalDate date, String placeName) {
        return new ChatbotApplyBlockDto(blockId, timeTableId, date, "P1", placeName, "12", "addr",
                "thumb", "Type3", BigDecimal.valueOf(35.1), BigDecimal.valueOf(129.0), T09, T10,
                BlockCategory.ATTRACTION, "memo");
    }

    private PlanSnapshot snapshot(List<TimetableDetailDto> timetables, List<TimetablePlaceBlockDetailDto> blocks) {
        return new PlanSnapshot(null, timetables, blocks);
    }

    private ChatbotApplyPlanDto target(List<ChatbotApplyTimetableDto> timetables,
                                        List<ChatbotApplyBlockDto> blocks) {
        return new ChatbotApplyPlanDto(null, timetables, blocks);
    }

    // ---------- 블록 ----------

    @Test
    @DisplayName("블록 — blockId 있고 필드 동일 → skip(update 목록에 안 들어감)")
    void block_unchanged_skipped() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of(block(11, 1, "경복궁")));
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)),
                List.of(targetBlock(11L, 1L, DAY1, "경복궁")));

        var result = reconciler.reconcile(saved, target);

        assertThat(result.createBlocks()).isEmpty();
        assertThat(result.updateBlocks()).isEmpty();
        assertThat(result.deleteBlockIds()).isEmpty();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("블록 — latitude/longitude 가 scale 만 다르고 값은 같음(BigDecimal.equals 함정) → skip"
            + " (08_통합_E2E_테스트_기록.md 실측 버그 회귀 방지)")
    void block_sameCoordinateDifferentScale_skipped() {
        TimetablePlaceBlockDetailDto savedBlock = new TimetablePlaceBlockDetailDto(11L, 1L, "P1", "경복궁", "12",
                "addr", "thumb", "Type3", new BigDecimal("35.15000000"), new BigDecimal("129.00000000"),
                T09, T10, BlockCategory.ATTRACTION, "memo");
        // 저장본은 DB numeric(10,8) 고정 스케일(8) — target 은 rehydrate 과정에서 double 왕복을 거쳐
        // BigDecimal.valueOf(double) 로 만들어져 스케일이 짧아질 수 있다. 값은 수학적으로 동일.
        ChatbotApplyBlockDto targetBlockDto = new ChatbotApplyBlockDto(11L, 1L, DAY1, "P1", "경복궁", "12",
                "addr", "thumb", "Type3", BigDecimal.valueOf(35.15), BigDecimal.valueOf(129.0),
                T09, T10, BlockCategory.ATTRACTION, "memo");

        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of(savedBlock));
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)), List.of(targetBlockDto));

        var result = reconciler.reconcile(saved, target);

        assertThat(result.updateBlocks()).isEmpty();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("블록 — blockId 있고 필드 다름(장소 교체) → UPDATE")
    void block_changed_update() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of(block(11, 1, "경복궁")));
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)),
                List.of(targetBlock(11L, 1L, DAY1, "창덕궁")));

        var result = reconciler.reconcile(saved, target);

        assertThat(result.updateBlocks()).hasSize(1);
        assertThat(result.updateBlocks().get(0).blockId()).isEqualTo(11L);
        assertThat(result.updateBlocks().get(0).placeName()).isEqualTo("창덕궁");
        assertThat(result.createBlocks()).isEmpty();
        assertThat(result.deleteBlockIds()).isEmpty();
    }

    @Test
    @DisplayName("블록 — blockId 있지만 saved 에 없음(T2~T4 사이 협업자가 먼저 지움) → CREATE(update 아님, 부활 방지)")
    void block_blockIdNotInSaved_treatedAsCreate() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of());
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)),
                List.of(targetBlock(999L, 1L, DAY1, "경복궁")));

        var result = reconciler.reconcile(saved, target);

        assertThat(result.createBlocks()).hasSize(1);
        assertThat(result.updateBlocks()).isEmpty();
    }

    @Test
    @DisplayName("블록 — blockId 없음 → CREATE")
    void block_noBlockId_create() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of());
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)),
                List.of(targetBlock(null, 1L, DAY1, "새 장소")));

        var result = reconciler.reconcile(saved, target);

        assertThat(result.createBlocks()).hasSize(1);
        assertThat(result.createBlocks().get(0).placeName()).isEqualTo("새 장소");
    }

    @Test
    @DisplayName("블록 — saved 에 있는데 target 어느 blockId 로도 안 걸림 → DELETE")
    void block_notInTarget_delete() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of(block(11, 1, "경복궁")));
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)), List.of());

        var result = reconciler.reconcile(saved, target);

        assertThat(result.deleteBlockIds()).containsExactly(11L);
    }

    @Test
    @DisplayName("블록 — 같은 날 안에서 다른 timeTableId(다른 날)로 이동 → UPDATE 로 감지")
    void block_movedToDifferentDay_detectedAsChanged() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1), timetable(2, DAY2)),
                List.of(block(11, 1, "경복궁")));
        // target 은 같은 필드값이지만 date 가 DAY2 로 바뀜(= 다른 timeTableId 를 의미)
        ChatbotApplyPlanDto target = target(
                List.of(targetTimetable(1L, DAY1), targetTimetable(2L, DAY2)),
                List.of(targetBlock(11L, 2L, DAY2, "경복궁")));

        var result = reconciler.reconcile(saved, target);

        assertThat(result.updateBlocks()).hasSize(1);
        assertThat(result.updateBlocks().get(0).timeTableId()).isEqualTo(2L);
    }

    // ---------- 타임테이블 ----------

    @Test
    @DisplayName("타임테이블 — timeTableId 있고 창(시작/끝) 동일 → skip")
    void timetable_unchanged_skipped() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of());
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)), List.of());

        var result = reconciler.reconcile(saved, target);

        assertThat(result.updateTimeTables()).isEmpty();
        assertThat(result.createTimeTables()).isEmpty();
        assertThat(result.deleteTimeTableIds()).isEmpty();
    }

    @Test
    @DisplayName("타임테이블 — timeTableId 있고 창 확장 → UPDATE")
    void timetable_windowChanged_update() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of());
        ChatbotApplyTimetableDto widened = new ChatbotApplyTimetableDto(1L, DAY1, LocalTime.of(8, 0),
                LocalTime.of(22, 0));
        ChatbotApplyPlanDto target = target(List.of(widened), List.of());

        var result = reconciler.reconcile(saved, target);

        assertThat(result.updateTimeTables()).hasSize(1);
        assertThat(result.updateTimeTables().get(0).timeTableStartTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    @DisplayName("타임테이블 — timeTableId 있지만 saved 에 없음 → CREATE")
    void timetable_idNotInSaved_create() {
        PlanSnapshot saved = snapshot(List.of(), List.of());
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(999L, DAY1)), List.of());

        var result = reconciler.reconcile(saved, target);

        assertThat(result.createTimeTables()).hasSize(1);
        assertThat(result.updateTimeTables()).isEmpty();
    }

    @Test
    @DisplayName("타임테이블 — timeTableId 없음 + saved 에 그 날짜 있음 → UPDATE(날짜로 매칭)")
    void timetable_noId_matchedByDate_update() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of());
        ChatbotApplyTimetableDto widened = new ChatbotApplyTimetableDto(null, DAY1, LocalTime.of(8, 0), T20);
        ChatbotApplyPlanDto target = target(List.of(widened), List.of());

        var result = reconciler.reconcile(saved, target);

        assertThat(result.updateTimeTables()).hasSize(1);
        assertThat(result.updateTimeTables().get(0).timeTableId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("타임테이블 — timeTableId 없음 + saved 에 그 날짜 없음(새 날) → CREATE")
    void timetable_noId_dateNotInSaved_create() {
        PlanSnapshot saved = snapshot(List.of(), List.of());
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(null, DAY2)), List.of());

        var result = reconciler.reconcile(saved, target);

        assertThat(result.createTimeTables()).hasSize(1);
        assertThat(result.createTimeTables().get(0).date()).isEqualTo(DAY2);
    }

    @Test
    @DisplayName("타임테이블 — saved 에 있는데 target 어느 timeTableId/date 로도 안 걸림 → DELETE")
    void timetable_notInTarget_delete() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1), timetable(2, DAY2)), List.of());
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)), List.of());

        var result = reconciler.reconcile(saved, target);

        assertThat(result.deleteTimeTableIds()).containsExactly(2L);
    }

    // ---------- 빈 diff ----------

    @Test
    @DisplayName("target == saved 완전 동일 → ReconcileResult.isEmpty() true (설계 §5-6)")
    void identicalSnapshot_isEmpty() {
        PlanSnapshot saved = snapshot(List.of(timetable(1, DAY1)), List.of(block(11, 1, "경복궁")));
        ChatbotApplyPlanDto target = target(List.of(targetTimetable(1L, DAY1)),
                List.of(targetBlock(11L, 1L, DAY1, "경복궁")));

        var result = reconciler.reconcile(saved, target);

        assertThat(result.isEmpty()).isTrue();
    }
}
