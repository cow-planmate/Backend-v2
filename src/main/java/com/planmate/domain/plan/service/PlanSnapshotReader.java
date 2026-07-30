package com.planmate.domain.plan.service;

import com.planmate.domain.plan.dto.PlanFrameDetailDto;
import com.planmate.domain.plan.dto.TimetableDetailDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDetailDto;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.timetable.entity.TimeTable;
import com.planmate.domain.timetable.entity.TimeTablePlaceBlock;
import com.planmate.domain.timetable.repository.TimeTablePlaceBlockRepository;
import com.planmate.domain.timetable.repository.TimeTableRepository;
import com.sharedsync.shared.storage.PresenceStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import sharedsync.cache.PlanCache;
import sharedsync.cache.TimeTableCache;
import sharedsync.cache.TimeTablePlaceBlockCache;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Plan 조회 시 SharedSync 캐시와 DB 중 최신 상태를 골라 읽는다.
 *
 * <p>SharedSync는 STOMP 편집 결과를 Redis 캐시에만 기록하고, DB에는 주기 동기화
 * (sharedsync.presence.periodic-sync-interval, 기본 30초) 또는 마지막 세션 종료 후
 * 지연 동기화(sharedsync.presence.sync-delay, 기본 5초) 시점에만 반영한다.
 * 그래서 DB만 읽으면 편집 직후 조회에서 이전 상태가 그대로 나온다.
 *
 * <p>캐시에 해당 Plan이 살아 있으면 캐시가 최신이므로 캐시를 우선 읽고,
 * 동기화가 끝나 캐시가 비워졌으면 DB로 폴백한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanSnapshotReader {

    private final PlanCache planCache;
    private final TimeTableCache timeTableCache;
    private final TimeTablePlaceBlockCache timeTablePlaceBlockCache;
    private final PresenceStorage presenceStorage;
    private final TimeTableRepository timeTableRepository;
    private final TimeTablePlaceBlockRepository timeTablePlaceBlockRepository;

    /**
     * 조회 시점의 Plan 전체 상태.
     */
    public record PlanSnapshot(
            PlanFrameDetailDto planFrame,
            List<TimetableDetailDto> timetables,
            List<TimetablePlaceBlockDetailDto> placeBlocks
    ) {
    }

    /**
     * @param plan 접근 검증에 사용한 DB Plan 엔티티. 캐시 미스이거나 캐시가 불완전할 때의 폴백으로도 쓴다.
     */
    public PlanSnapshot read(Plan plan) {
        UUID planId = plan.getPlanId();

        // 동기화가 진행 중이면 끝날 때까지 대기(최대 5초). 캐시 삭제와 DB 커밋 사이의 중간 상태를 읽지 않기 위함.
        presenceStorage.waitForSync(planId.toString());

        Plan cachedPlan = planCache.findById(planId).orElse(null);
        if (cachedPlan == null) {
            return readFromDatabase(plan);
        }

        try {
            return readFromCache(plan, cachedPlan, planId);
        } catch (Exception e) {
            // 캐시 구조가 깨졌더라도 조회 자체는 실패하지 않도록 DB로 폴백한다.
            log.warn("[PlanSnapshot] 캐시 조회 실패로 DB 폴백. planId={}", planId, e);
            return readFromDatabase(plan);
        }
    }

    private PlanSnapshot readFromCache(Plan plan, Plan cachedPlan, UUID planId) {
        List<TimeTable> cachedTimeTables = new ArrayList<>(timeTableCache.findByParentId(planId));
        cachedTimeTables.sort(
                Comparator.comparing(TimeTable::getDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                        .thenComparing(TimeTable::getTimeTableId,
                                Comparator.nullsLast(Comparator.<Long>naturalOrder())));

        List<TimetableDetailDto> timetables = cachedTimeTables.stream()
                .map(TimetableDetailDto::from)
                .toList();

        // DB 쿼리(findByPlanIdOrderByDateAndStartTime)와 같은 정렬을 유지하기 위해
        // 날짜순 타임테이블을 순회하면서 각 타임테이블 안에서 시작시각순으로 정렬한다.
        List<TimetablePlaceBlockDetailDto> placeBlocks = new ArrayList<>();
        for (TimeTable timeTable : cachedTimeTables) {
            Long timeTableId = timeTable.getTimeTableId();
            if (timeTableId == null) {
                continue;
            }

            List<TimeTablePlaceBlock> blocks =
                    new ArrayList<>(timeTablePlaceBlockCache.findByParentId((Object) timeTableId));
            blocks.sort(Comparator.comparing(TimeTablePlaceBlock::getBlockStartTime,
                    Comparator.nullsLast(Comparator.<LocalTime>naturalOrder())));

            for (TimeTablePlaceBlock block : blocks) {
                // 캐시에서 복원한 블록의 timeTable은 em.getReference() 프록시라 초기화하면
                // 아직 DB에 없는 신규 타임테이블에서 EntityNotFoundException이 난다.
                // 이미 알고 있는 부모 ID를 넘겨 프록시 접근을 피한다.
                placeBlocks.add(TimetablePlaceBlockDetailDto.from(block, timeTableId));
            }
        }

        return new PlanSnapshot(resolvePlanFrame(plan, cachedPlan), timetables, placeBlocks);
    }

    /**
     * 계획 이름 등은 실시간 편집 대상이므로 캐시본을 우선한다.
     * 단 캐시에 destination 참조가 없으면 PlanFrameDetailDto를 만들 수 없어 DB본을 쓴다.
     */
    private PlanFrameDetailDto resolvePlanFrame(Plan plan, Plan cachedPlan) {
        if (cachedPlan.getDestination() == null) {
            return PlanFrameDetailDto.from(plan);
        }
        return PlanFrameDetailDto.from(cachedPlan);
    }

    private PlanSnapshot readFromDatabase(Plan plan) {
        List<TimetableDetailDto> timetables =
                timeTableRepository.findByPlanPlanIdOrderByDate(plan.getPlanId()).stream()
                        .map(TimetableDetailDto::from)
                        .toList();
        List<TimetablePlaceBlockDetailDto> placeBlocks =
                timeTablePlaceBlockRepository.findByPlanIdOrderByDateAndStartTime(plan.getPlanId()).stream()
                        .map(TimetablePlaceBlockDetailDto::from)
                        .toList();

        return new PlanSnapshot(PlanFrameDetailDto.from(plan), timetables, placeBlocks);
    }
}
