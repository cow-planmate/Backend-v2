package com.planmate.domain.plan.service;

import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.timetable.entity.TimeTable;
import com.planmate.domain.timetable.entity.TimeTablePlaceBlock;
import com.planmate.domain.timetable.enums.BlockCategory;
import com.planmate.domain.timetable.repository.TimeTablePlaceBlockRepository;
import com.planmate.domain.timetable.repository.TimeTableRepository;
import com.sharedsync.shared.storage.PresenceStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sharedsync.cache.PlanCache;
import sharedsync.cache.TimeTableCache;
import sharedsync.cache.TimeTablePlaceBlockCache;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PlanSnapshotReaderTest {

    private static final UUID PLAN_ID = UUID.randomUUID();

    @Mock
    private PlanCache planCache;
    @Mock
    private TimeTableCache timeTableCache;
    @Mock
    private TimeTablePlaceBlockCache timeTablePlaceBlockCache;
    @Mock
    private PresenceStorage presenceStorage;
    @Mock
    private TimeTableRepository timeTableRepository;
    @Mock
    private TimeTablePlaceBlockRepository timeTablePlaceBlockRepository;

    @InjectMocks
    private PlanSnapshotReader planSnapshotReader;

    @Test
    @DisplayName("캐시에 플랜이 없으면 DB에서 읽는다")
    void read_cacheMiss_fallsBackToDatabase() {
        Plan plan = planStub("서울 여행");
        given(planCache.findById(PLAN_ID)).willReturn(Optional.empty());

        TimeTable timeTable = timeTableStub(1L, LocalDate.of(2026, 8, 1), plan);
        given(timeTableRepository.findByPlanPlanIdOrderByDate(PLAN_ID)).willReturn(List.of(timeTable));
        given(timeTablePlaceBlockRepository.findByPlanIdOrderByDateAndStartTime(PLAN_ID))
                .willReturn(List.of(blockStub(10L, timeTable, "경복궁", LocalTime.of(10, 0))));

        PlanSnapshotReader.PlanSnapshot snapshot = planSnapshotReader.read(plan);

        assertThat(snapshot.planFrame().planName()).isEqualTo("서울 여행");
        assertThat(snapshot.timetables()).hasSize(1);
        assertThat(snapshot.placeBlocks()).extracting("placeName").containsExactly("경복궁");
        assertThat(snapshot.source()).isEqualTo(PlanSnapshotReader.Source.DATABASE);
        verifyNoInteractions(timeTableCache, timeTablePlaceBlockCache);
    }

    @Test
    @DisplayName("캐시에 플랜이 있으면 DB 대신 캐시의 최신 상태를 읽는다")
    void read_cacheHit_readsFromCache() {
        Plan dbPlan = planStub("서울 여행");
        Plan cachedPlan = planStub("수정된 서울 여행");
        given(planCache.findById(PLAN_ID)).willReturn(Optional.of(cachedPlan));

        TimeTable timeTable = timeTableStub(1L, LocalDate.of(2026, 8, 1), cachedPlan);
        given(timeTableCache.findByParentId(PLAN_ID)).willReturn(List.of(timeTable));
        given(timeTablePlaceBlockCache.findByParentId((Object) 1L))
                .willReturn(List.of(blockStub(10L, timeTable, "수정된 장소", LocalTime.of(10, 0))));

        PlanSnapshotReader.PlanSnapshot snapshot = planSnapshotReader.read(dbPlan);

        assertThat(snapshot.planFrame().planName()).isEqualTo("수정된 서울 여행");
        assertThat(snapshot.placeBlocks()).extracting("placeName").containsExactly("수정된 장소");
        assertThat(snapshot.placeBlocks()).extracting("timeTableId").containsExactly(1L);
        assertThat(snapshot.source()).isEqualTo(PlanSnapshotReader.Source.CACHE);
        verifyNoInteractions(timeTableRepository, timeTablePlaceBlockRepository);
    }

    @Test
    @DisplayName("캐시 조회 시 진행 중인 DB 동기화가 끝날 때까지 먼저 대기한다")
    void read_waitsForPendingSync() {
        Plan plan = planStub("서울 여행");
        given(planCache.findById(PLAN_ID)).willReturn(Optional.empty());
        given(timeTableRepository.findByPlanPlanIdOrderByDate(PLAN_ID)).willReturn(List.of());
        given(timeTablePlaceBlockRepository.findByPlanIdOrderByDateAndStartTime(PLAN_ID)).willReturn(List.of());

        planSnapshotReader.read(plan);

        verify(presenceStorage).waitForSync(PLAN_ID.toString());
    }

    @Test
    @DisplayName("캐시 조회가 실패해도 DB로 폴백해 응답한다")
    void read_cacheFailure_fallsBackToDatabase() {
        Plan plan = planStub("서울 여행");
        given(planCache.findById(PLAN_ID)).willReturn(Optional.of(plan));
        willThrow(new IllegalStateException("캐시 손상")).given(timeTableCache).findByParentId(any(UUID.class));

        TimeTable timeTable = timeTableStub(1L, LocalDate.of(2026, 8, 1), plan);
        given(timeTableRepository.findByPlanPlanIdOrderByDate(PLAN_ID)).willReturn(List.of(timeTable));
        given(timeTablePlaceBlockRepository.findByPlanIdOrderByDateAndStartTime(PLAN_ID)).willReturn(List.of());

        PlanSnapshotReader.PlanSnapshot snapshot = planSnapshotReader.read(plan);

        assertThat(snapshot.timetables()).hasSize(1);
        // 폴백본은 늦을 수 있다 — [반영]이 이 값을 보고 반영을 거부한다(07 §12-2).
        assertThat(snapshot.source()).isEqualTo(PlanSnapshotReader.Source.CACHE_FAILED_DATABASE);
    }

    @Test
    @DisplayName("캐시 조회 결과는 날짜순 + 시작시각순으로 정렬한다 (DB 쿼리 정렬과 동일)")
    void read_cacheHit_sortsByDateThenStartTime() {
        Plan plan = planStub("서울 여행");
        given(planCache.findById(PLAN_ID)).willReturn(Optional.of(plan));

        TimeTable day2 = timeTableStub(2L, LocalDate.of(2026, 8, 2), plan);
        TimeTable day1 = timeTableStub(1L, LocalDate.of(2026, 8, 1), plan);
        given(timeTableCache.findByParentId(PLAN_ID)).willReturn(List.of(day2, day1));

        given(timeTablePlaceBlockCache.findByParentId((Object) 1L)).willReturn(List.of(
                blockStub(11L, day1, "오후", LocalTime.of(14, 0)),
                blockStub(10L, day1, "오전", LocalTime.of(9, 0))));
        given(timeTablePlaceBlockCache.findByParentId((Object) 2L)).willReturn(List.of(
                blockStub(20L, day2, "둘째날", LocalTime.of(9, 0))));

        PlanSnapshotReader.PlanSnapshot snapshot = planSnapshotReader.read(plan);

        assertThat(snapshot.timetables()).extracting("timeTableId").containsExactly(1L, 2L);
        assertThat(snapshot.placeBlocks()).extracting("placeName").containsExactly("오전", "오후", "둘째날");
    }

    private Plan planStub(String planName) {
        return Plan.builder()
                .planId(PLAN_ID)
                .planName(planName)
                .adultCount(1)
                .childCount(0)
                .isShared(true)
                .destination(Destination.builder()
                        .destinationId(1)
                        .destinationName("서울")
                        .build())
                .build();
    }

    private TimeTable timeTableStub(Long id, LocalDate date, Plan plan) {
        return TimeTable.builder()
                .timeTableId(id)
                .date(date)
                .timeTableStartTime(LocalTime.of(9, 0))
                .timeTableEndTime(LocalTime.of(20, 0))
                .plan(plan)
                .build();
    }

    private TimeTablePlaceBlock blockStub(Long id, TimeTable timeTable, String placeName, LocalTime startTime) {
        return TimeTablePlaceBlock.builder()
                .blockId(id)
                .timeTable(timeTable)
                .placeName(placeName)
                .blockCategory(BlockCategory.ATTRACTION)
                .blockStartTime(startTime)
                .blockEndTime(startTime.plusHours(1))
                .build();
    }
}
