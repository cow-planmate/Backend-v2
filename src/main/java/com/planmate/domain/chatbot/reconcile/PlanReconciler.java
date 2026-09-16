package com.planmate.domain.chatbot.reconcile;

import com.planmate.domain.chatbot.dto.ChatbotApplyBlockDto;
import com.planmate.domain.chatbot.dto.ChatbotApplyPlanDto;
import com.planmate.domain.chatbot.dto.ChatbotApplyTimetableDto;
import com.planmate.domain.plan.dto.TimetableDetailDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDetailDto;
import com.planmate.domain.plan.service.PlanSnapshotReader.PlanSnapshot;
import com.planmate.domain.timetable.enums.BlockCategory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 저장본(saved)과 [반영] working copy(target)를 id 로 대조해 create/update/delete 연산 리스트를 낸다.
 *
 * <p>순수 함수 — DB/캐시/네트워크 접근 없음. 블록은 자연키({@code date, blockStartTime}) 매칭을
 * 하지 않는다({@code blockId} 유무만 본다) — planmate-ai/project_ai/14 §5 원문과의 의도적 이탈, 근거는
 * 설계 §5-1. 적용 순서(leaf-first)는 이 클래스의 책임이 아니라 호출자({@code ChatbotApplyService})가
 * 정한다.
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §5.
 */
@Component
public class PlanReconciler {

    public record ReconcileResult(
            List<TimeTableCreateOp> createTimeTables,
            List<TimeTableUpdateOp> updateTimeTables,
            List<Long> deleteTimeTableIds,
            List<BlockCreateOp> createBlocks,
            List<BlockUpdateOp> updateBlocks,
            List<Long> deleteBlockIds
    ) {
        public boolean isEmpty() {
            return createTimeTables.isEmpty() && updateTimeTables.isEmpty() && deleteTimeTableIds.isEmpty()
                    && createBlocks.isEmpty() && updateBlocks.isEmpty() && deleteBlockIds.isEmpty();
        }
    }

    public record TimeTableCreateOp(LocalDate date, LocalTime timeTableStartTime, LocalTime timeTableEndTime) {
    }

    public record TimeTableUpdateOp(Long timeTableId, LocalDate date, LocalTime timeTableStartTime,
                                     LocalTime timeTableEndTime) {
    }

    /**
     * {@code timeTableId} 는 target 이 준 값(있으면). 새 날짜를 가리키면 null 일 수 있어, 실제 반영은
     * 호출자가 (§5-4 leaf-first 순서로) 타임테이블부터 만들고 나서 {@code date} 로 해석한다.
     */
    public record BlockCreateOp(LocalDate date, Long timeTableId, String placeId, String placeName,
                                 String placeContentTypeId, String placeAddress, String placeThumbnailUrl,
                                 String placeCopyrightDivCd, BigDecimal latitude, BigDecimal longitude,
                                 LocalTime blockStartTime, LocalTime blockEndTime, BlockCategory blockCategory,
                                 String memo) {
    }

    public record BlockUpdateOp(Long blockId, LocalDate date, Long timeTableId, String placeId, String placeName,
                                 String placeContentTypeId, String placeAddress, String placeThumbnailUrl,
                                 String placeCopyrightDivCd, BigDecimal latitude, BigDecimal longitude,
                                 LocalTime blockStartTime, LocalTime blockEndTime, BlockCategory blockCategory,
                                 String memo) {
    }

    public ReconcileResult reconcile(PlanSnapshot saved, ChatbotApplyPlanDto target) {
        TimeTableDiff timeTableDiff = reconcileTimeTables(saved.timetables(), target.timetables());
        BlockDiff blockDiff = reconcileBlocks(saved, target.placeBlocks());

        return new ReconcileResult(
                timeTableDiff.creates, timeTableDiff.updates, timeTableDiff.deleteIds,
                blockDiff.creates, blockDiff.updates, blockDiff.deleteIds);
    }

    // ---------- 타임테이블 (§5-3) ----------

    private record TimeTableDiff(List<TimeTableCreateOp> creates, List<TimeTableUpdateOp> updates,
                                  List<Long> deleteIds) {
    }

    private TimeTableDiff reconcileTimeTables(List<TimetableDetailDto> saved,
                                               List<ChatbotApplyTimetableDto> target) {
        Map<Long, TimetableDetailDto> savedById = new HashMap<>();
        Map<LocalDate, TimetableDetailDto> savedByDate = new HashMap<>();
        for (TimetableDetailDto t : saved) {
            savedById.put(t.timeTableId(), t);
            savedByDate.put(t.date(), t);
        }

        List<TimeTableCreateOp> creates = new ArrayList<>();
        List<TimeTableUpdateOp> updates = new ArrayList<>();
        Set<Long> matchedIds = new HashSet<>();

        for (ChatbotApplyTimetableDto t : target) {
            TimetableDetailDto matched = null;
            if (t.timeTableId() != null) {
                matched = savedById.get(t.timeTableId());
            } else {
                matched = savedByDate.get(t.date());
            }

            if (matched == null) {
                creates.add(new TimeTableCreateOp(t.date(), t.timeTableStartTime(), t.timeTableEndTime()));
                continue;
            }

            matchedIds.add(matched.timeTableId());
            boolean changed = !Objects.equals(matched.date(), t.date())
                    || !Objects.equals(matched.timeTableStartTime(), t.timeTableStartTime())
                    || !Objects.equals(matched.timeTableEndTime(), t.timeTableEndTime());
            if (changed) {
                updates.add(new TimeTableUpdateOp(matched.timeTableId(), t.date(), t.timeTableStartTime(),
                        t.timeTableEndTime()));
            }
        }

        List<Long> deleteIds = saved.stream()
                .map(TimetableDetailDto::timeTableId)
                .filter(id -> !matchedIds.contains(id))
                .toList();

        return new TimeTableDiff(creates, updates, deleteIds);
    }

    // ---------- 블록 (§5-2) ----------

    private record BlockDiff(List<BlockCreateOp> creates, List<BlockUpdateOp> updates, List<Long> deleteIds) {
    }

    private BlockDiff reconcileBlocks(PlanSnapshot saved, List<ChatbotApplyBlockDto> target) {
        Map<Long, TimetablePlaceBlockDetailDto> savedById = new HashMap<>();
        for (TimetablePlaceBlockDetailDto b : saved.placeBlocks()) {
            savedById.put(b.blockId(), b);
        }
        // 저장본 블록이 속한 날짜 — target.date() 와 비교해 "다른 날로 이동"을 감지하는 데 쓴다
        // (saved 블록 자체엔 date 필드가 없고 timeTableId 만 있음).
        Map<Long, LocalDate> savedTimeTableDateById = new HashMap<>();
        for (TimetableDetailDto t : saved.timetables()) {
            savedTimeTableDateById.put(t.timeTableId(), t.date());
        }

        List<BlockCreateOp> creates = new ArrayList<>();
        List<BlockUpdateOp> updates = new ArrayList<>();
        Set<Long> matchedIds = new HashSet<>();

        for (ChatbotApplyBlockDto b : target) {
            TimetablePlaceBlockDetailDto matched = b.blockId() == null ? null : savedById.get(b.blockId());

            if (matched == null) {
                creates.add(new BlockCreateOp(b.date(), b.timeTableId(), b.placeId(), b.placeName(),
                        b.placeContentTypeId(), b.placeAddress(), b.placeThumbnailUrl(), b.placeCopyrightDivCd(),
                        b.latitude(), b.longitude(), b.blockStartTime(), b.blockEndTime(), b.blockCategory(),
                        b.memo()));
                continue;
            }

            matchedIds.add(matched.blockId());
            LocalDate matchedDate = savedTimeTableDateById.get(matched.timeTableId());
            boolean changed = !Objects.equals(matchedDate, b.date())
                    || !Objects.equals(matched.placeId(), b.placeId())
                    || !Objects.equals(matched.placeName(), b.placeName())
                    || !Objects.equals(matched.placeContentTypeId(), b.placeContentTypeId())
                    || !Objects.equals(matched.placeAddress(), b.placeAddress())
                    || !Objects.equals(matched.placeThumbnailUrl(), b.placeThumbnailUrl())
                    || !Objects.equals(matched.placeCopyrightDivCd(), b.placeCopyrightDivCd())
                    || !bigDecimalEquals(matched.latitude(), b.latitude())
                    || !bigDecimalEquals(matched.longitude(), b.longitude())
                    || !Objects.equals(matched.blockStartTime(), b.blockStartTime())
                    || !Objects.equals(matched.blockEndTime(), b.blockEndTime())
                    || matched.blockCategory() != b.blockCategory();

            if (changed) {
                updates.add(new BlockUpdateOp(matched.blockId(), b.date(), b.timeTableId(), b.placeId(),
                        b.placeName(), b.placeContentTypeId(), b.placeAddress(), b.placeThumbnailUrl(),
                        b.placeCopyrightDivCd(), b.latitude(), b.longitude(), b.blockStartTime(), b.blockEndTime(),
                        b.blockCategory(), b.memo()));
            }
        }

        List<Long> deleteIds = saved.placeBlocks().stream()
                .map(TimetablePlaceBlockDetailDto::blockId)
                .filter(id -> !matchedIds.contains(id))
                .toList();

        return new BlockDiff(creates, updates, deleteIds);
    }

    /**
     * {@code BigDecimal.equals()}는 scale 까지 비교해 {@code 129.16}과 {@code 129.1600000}을
     * 다른 값으로 본다. 저장본의 latitude/longitude는 앱 DB 컬럼({@code numeric(10,8)}/
     * {@code numeric(11,8)}, 스케일 고정)에서 오지만, target 은 {@code ChatbotApplyService}가
     * tourdata 를 {@code double} 로 재조회해 {@code BigDecimal.valueOf(double)}로 만든 값이라
     * 스케일이 들쭉날쭉하다 — 같은 좌표인데도 이 메서드 없이 비교하면 매번 "변경됨"으로 오판해
     * 불필요한 UPDATE 가 나간다(실측: 08_통합_E2E_테스트_기록.md §2 발견).
     */
    private static boolean bigDecimalEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }
}
