package com.planmate.domain.plan.service;

import com.planmate.common.exception.domain.AuthenticationRequiredException;
import com.planmate.common.exception.domain.PlanAccessDeniedException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.dto.GetPlanResponse;
import com.planmate.domain.plan.dto.GetShareStatusResponse;
import com.planmate.domain.plan.dto.PlanFrameDetailDto;
import com.planmate.domain.plan.dto.TimetableDetailDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDetailDto;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.plan.validator.PlanAccessValidator;
import com.planmate.domain.timetable.entity.TimeTable;
import com.planmate.domain.timetable.entity.TimeTablePlaceBlock;
import com.planmate.domain.timetable.enums.BlockCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sharedsync.cache.PlanCache;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final Integer DESTINATION_ID = 1;

    @Mock
    private PlanRepository planRepository;
    @Mock
    private PlanAccessValidator planAccessValidator;
    @Mock
    private PlanSnapshotReader planSnapshotReader;
    @Mock
    private PlanCache planCache;

    @InjectMocks
    private PlanService planService;

    /** 캐시/DB 중 어디서 읽을지는 PlanSnapshotReaderTest에서 검증한다. 여기서는 조립 결과만 주입한다. */
    private void givenSnapshot(Plan plan,
                               List<TimetableDetailDto> timetables,
                               List<TimetablePlaceBlockDetailDto> placeBlocks) {
        given(planSnapshotReader.read(plan)).willReturn(new PlanSnapshotReader.PlanSnapshot(
                PlanFrameDetailDto.from(plan), timetables, placeBlocks));
    }

    private Destination destination() {
        return Destination.builder()
                .destinationId(DESTINATION_ID)
                .destinationName("서울")
                .build();
    }

    private Plan savedPlanStub() {
        return Plan.builder().planId(PLAN_ID).build();
    }

    private Plan sharedPlanStub(boolean isShared) {
        return Plan.builder()
                .planId(PLAN_ID)
                .planName("서울 여행")
                .adultCount(1)
                .childCount(0)
                .isShared(isShared)
                .destination(destination())
                .build();
    }

    // ---------- getPlan (#25) ----------

    @Test
    @DisplayName("플랜 상세 조회 성공 - 프레임+타임테이블+블록 조립")
    void getPlan_success() {
        Plan plan = sharedPlanStub(true);
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));

        TimeTable timeTable = TimeTable.builder()
                .timeTableId(1L)
                .date(LocalDate.of(2026, 8, 1))
                .timeTableStartTime(LocalTime.of(9, 0))
                .timeTableEndTime(LocalTime.of(20, 0))
                .plan(plan)
                .build();

        TimeTablePlaceBlock block = TimeTablePlaceBlock.builder()
                .blockId(10L)
                .timeTable(timeTable)
                .placeName("경복궁")
                .blockCategory(BlockCategory.ATTRACTION)
                .blockStartTime(LocalTime.of(10, 0))
                .blockEndTime(LocalTime.of(12, 0))
                .build();

        givenSnapshot(plan,
                List.of(TimetableDetailDto.from(timeTable)),
                List.of(TimetablePlaceBlockDetailDto.from(block)));

        GetPlanResponse response = planService.getPlan(PLAN_ID);

        assertThat(response.planFrame().planId()).isEqualTo(PLAN_ID);
        assertThat(response.planFrame().destinationName()).isEqualTo("서울");
        assertThat(response.timetables()).hasSize(1);
        assertThat(response.timetables().get(0).timeTableId()).isEqualTo(1L);
        assertThat(response.placeBlocks()).hasSize(1);
        assertThat(response.placeBlocks().get(0).placeName()).isEqualTo("경복궁");
    }

    @Test
    @DisplayName("플랜 상세 조회 실패 - 플랜 없음")
    void getPlan_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> planService.getPlan(PLAN_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ---------- getCompletePlan (#26, 원본 #26/27 통합) ----------

    @Test
    @DisplayName("완료된 플랜 조회 성공 - is_shared=true면 로그인 없이도(userId=null) 통과, 멤버 검증 안 함")
    void getCompletePlan_success_sharedTrue_anonymous() {
        Plan plan = sharedPlanStub(true);
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));
        givenSnapshot(plan, List.of(), List.of());

        GetCompletePlanResponse response = planService.getCompletePlan(null, PLAN_ID);

        assertThat(response.planFrame().planId()).isEqualTo(PLAN_ID);
        verifyNoInteractions(planAccessValidator);
    }

    @Test
    @DisplayName("완료된 플랜 조회 실패 - is_shared=false, 로그인 안 함(userId=null)이면 401")
    void getCompletePlan_fail_sharedFalse_noAuth() {
        Plan plan = sharedPlanStub(false);
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));

        assertThatThrownBy(() -> planService.getCompletePlan(null, PLAN_ID))
                .isInstanceOf(AuthenticationRequiredException.class);

        verifyNoInteractions(planAccessValidator);
    }

    @Test
    @DisplayName("완료된 플랜 조회 실패 - is_shared=false, 로그인했지만 멤버 아니면 403")
    void getCompletePlan_fail_sharedFalse_notMember() {
        Plan plan = sharedPlanStub(false);
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));
        willThrow(new PlanAccessDeniedException()).given(planAccessValidator).validateMember(PLAN_ID, USER_ID);

        assertThatThrownBy(() -> planService.getCompletePlan(USER_ID, PLAN_ID))
                .isInstanceOf(PlanAccessDeniedException.class);
    }

    @Test
    @DisplayName("완료된 플랜 조회 성공 - is_shared=false, 로그인했고 멤버면 통과")
    void getCompletePlan_success_sharedFalse_member() {
        Plan plan = sharedPlanStub(false);
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));
        given(planAccessValidator.validateMember(PLAN_ID, USER_ID)).willReturn(true);
        givenSnapshot(plan, List.of(), List.of());

        GetCompletePlanResponse response = planService.getCompletePlan(USER_ID, PLAN_ID);

        assertThat(response.planFrame().planId()).isEqualTo(PLAN_ID);
        verify(planAccessValidator).validateMember(PLAN_ID, USER_ID);
    }

    @Test
    @DisplayName("완료된 플랜 조회 실패 - 플랜 없음")
    void getCompletePlan_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> planService.getCompletePlan(USER_ID, PLAN_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ---------- deletePlan (#28) ----------

    @Test
    @DisplayName("플랜 삭제 성공 - 소프트 삭제(deletedAt 설정)")
    void deletePlan_success() {
        Plan plan = savedPlanStub();
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));

        planService.deletePlan(PLAN_ID);

        assertThat(plan.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("플랜 삭제 실패 - 플랜 없음")
    void deletePlan_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> planService.deletePlan(PLAN_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ---------- editPlanName (#31) ----------

    @Test
    @DisplayName("플랜 이름 수정 성공")
    void editPlanName_success() {
        Plan plan = savedPlanStub();
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));

        planService.editPlanName(PLAN_ID, "새로운 플랜 이름");

        assertThat(plan.getPlanName()).isEqualTo("새로운 플랜 이름");
    }

    @Test
    @DisplayName("플랜 이름 수정 실패 - 플랜 없음")
    void editPlanName_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> planService.editPlanName(PLAN_ID, "새로운 플랜 이름"))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ---------- getShareStatus (#32 GET) ----------

    @Test
    @DisplayName("공유 상태 조회 성공 - isShared=true")
    void getShareStatus_success_shared() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(sharedPlanStub(true)));

        GetShareStatusResponse response = planService.getShareStatus(PLAN_ID);

        assertThat(response.isShared()).isTrue();
    }

    @Test
    @DisplayName("공유 상태 조회 성공 - isShared=false")
    void getShareStatus_success_notShared() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(sharedPlanStub(false)));

        GetShareStatusResponse response = planService.getShareStatus(PLAN_ID);

        assertThat(response.isShared()).isFalse();
    }

    @Test
    @DisplayName("공유 상태 조회 실패 - 플랜 없음")
    void getShareStatus_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> planService.getShareStatus(PLAN_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ---------- updateShare (#32 PATCH) ----------

    @Test
    @DisplayName("공유 상태 변경 성공 - false에서 true로 전환")
    void updateShare_success_toTrue() {
        Plan plan = savedPlanStub();
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));

        planService.updateShare(PLAN_ID, true);

        assertThat(plan.isShared()).isTrue();
    }

    @Test
    @DisplayName("공유 상태 변경 성공 - true에서 false로 전환")
    void updateShare_success_toFalse() {
        Plan plan = sharedPlanStub(true);
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));

        planService.updateShare(PLAN_ID, false);

        assertThat(plan.isShared()).isFalse();
    }

    @Test
    @DisplayName("공유 상태 변경 실패 - 플랜 없음")
    void updateShare_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> planService.updateShare(PLAN_ID, true))
                .isInstanceOf(PlanNotFoundException.class);
    }
}
