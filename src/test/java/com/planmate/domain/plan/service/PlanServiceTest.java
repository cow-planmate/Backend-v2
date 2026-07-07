package com.planmate.domain.plan.service;

import com.planmate.common.exception.domain.AuthenticationRequiredException;
import com.planmate.common.exception.domain.DestinationNotFoundException;
import com.planmate.common.exception.domain.PlanAccessDeniedException;
import com.planmate.common.exception.domain.PlanDuplicateBlockTimeException;
import com.planmate.common.exception.domain.PlanDuplicateDateException;
import com.planmate.common.exception.domain.PlanInvalidBlockDateException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.dto.CreatePlanFrameRequest;
import com.planmate.domain.plan.dto.CreatePlanFrameResponse;
import com.planmate.domain.plan.dto.CreatePlanRequest;
import com.planmate.domain.plan.dto.CreatePlanResponse;
import com.planmate.domain.plan.dto.DeleteMultiplePlansResponse;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.dto.GetPlanResponse;
import com.planmate.domain.plan.dto.GetShareStatusResponse;
import com.planmate.domain.plan.dto.PlanFrameDto;
import com.planmate.domain.plan.dto.TimetableDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDto;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.plan.validator.PlanAccessValidator;
import com.planmate.domain.timetable.entity.TimeTable;
import com.planmate.domain.timetable.entity.TimeTablePlaceBlock;
import com.planmate.domain.timetable.enums.BlockCategory;
import com.planmate.domain.timetable.repository.TimeTablePlaceBlockRepository;
import com.planmate.domain.timetable.repository.TimeTableRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID PLAN_ID_2 = UUID.randomUUID();
    private static final Integer DESTINATION_ID = 1;

    @Mock
    private PlanRepository planRepository;
    @Mock
    private PlanMemberRepository planMemberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private DestinationRepository destinationRepository;
    @Mock
    private TimeTableRepository timeTableRepository;
    @Mock
    private TimeTablePlaceBlockRepository timeTablePlaceBlockRepository;
    @Mock
    private PlanAccessValidator planAccessValidator;

    @InjectMocks
    private PlanService planService;

    private Destination destination() {
        return Destination.builder()
                .destinationId(DESTINATION_ID)
                .destinationName("서울")
                .build();
    }

    private User user() {
        return User.builder().userId(USER_ID).nickname("tester").build();
    }

    private Plan savedPlanStub() {
        return Plan.builder().planId(PLAN_ID).build();
    }

    // ---------- createPlanFrame (#24) ----------

    @Test
    @DisplayName("플랜 프레임 생성 성공 - Plan/TimeTable/PlanMember(OWNER) 저장, isShared=true 명시")
    void createPlanFrame_success() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(destination()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
        given(planRepository.save(any(Plan.class))).willReturn(savedPlanStub());

        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                DESTINATION_ID, TransportationType.PUBLIC,
                List.of(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2)), 2, 1);

        CreatePlanFrameResponse response = planService.createPlanFrame(USER_ID, request);

        assertThat(response.planId()).isEqualTo(PLAN_ID);

        ArgumentCaptor<Plan> planCaptor = ArgumentCaptor.forClass(Plan.class);
        verify(planRepository).save(planCaptor.capture());
        Plan builtPlan = planCaptor.getValue();
        assertThat(builtPlan.getPlanName()).isEqualTo("서울");
        assertThat(builtPlan.isShared()).isTrue();
        assertThat(builtPlan.getTransportationType()).isEqualTo(TransportationType.PUBLIC);
        assertThat(builtPlan.getAdultCount()).isEqualTo(2);
        assertThat(builtPlan.getChildCount()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimeTable>> timeTableCaptor = ArgumentCaptor.forClass(List.class);
        verify(timeTableRepository).saveAll(timeTableCaptor.capture());
        List<TimeTable> savedTimeTables = timeTableCaptor.getValue();
        assertThat(savedTimeTables).hasSize(2);
        assertThat(savedTimeTables).extracting(TimeTable::getDate)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2));
        assertThat(savedTimeTables).allSatisfy(tt -> {
            assertThat(tt.getTimeTableStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(tt.getTimeTableEndTime()).isEqualTo(LocalTime.of(20, 0));
        });

        ArgumentCaptor<PlanMember> memberCaptor = ArgumentCaptor.forClass(PlanMember.class);
        verify(planMemberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(PlanMemberRole.OWNER);
    }

    @Test
    @DisplayName("플랜 프레임 생성 실패 - 목적지 없음")
    void createPlanFrame_fail_destinationNotFound() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.empty());

        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                DESTINATION_ID, TransportationType.PUBLIC, List.of(LocalDate.of(2026, 8, 1)), 1, 0);

        assertThatThrownBy(() -> planService.createPlanFrame(USER_ID, request))
                .isInstanceOf(DestinationNotFoundException.class);

        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("플랜 프레임 생성 실패 - 유저 없음(방어적 케이스)")
    void createPlanFrame_fail_userNotFound() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(destination()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                DESTINATION_ID, TransportationType.PUBLIC, List.of(LocalDate.of(2026, 8, 1)), 1, 0);

        assertThatThrownBy(() -> planService.createPlanFrame(USER_ID, request))
                .isInstanceOf(UserNotFoundException.class);

        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("플랜 프레임 생성 실패 - dates에 중복 날짜")
    void createPlanFrame_fail_duplicateDate() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(destination()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));

        LocalDate date = LocalDate.of(2026, 8, 1);
        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                DESTINATION_ID, TransportationType.PUBLIC, List.of(date, date), 1, 0);

        assertThatThrownBy(() -> planService.createPlanFrame(USER_ID, request))
                .isInstanceOf(PlanDuplicateDateException.class);

        verify(planRepository, never()).save(any());
    }

    // ---------- createPlan (#29) ----------

    @Test
    @DisplayName("플랜 전체 생성 성공 - 블록이 date로 올바른 TimeTable에 매칭됨")
    void createPlan_success() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(destination()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
        given(planRepository.save(any(Plan.class))).willReturn(savedPlanStub());
        given(timeTableRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        LocalDate day1 = LocalDate.of(2026, 8, 1);
        LocalDate day2 = LocalDate.of(2026, 8, 2);
        PlanFrameDto planFrame = new PlanFrameDto(DESTINATION_ID, TransportationType.PRIVATE, 2, 0);
        List<TimetableDto> timetables = List.of(
                new TimetableDto(day1, LocalTime.of(9, 0), LocalTime.of(20, 0)),
                new TimetableDto(day2, LocalTime.of(9, 0), LocalTime.of(20, 0)));
        List<TimetablePlaceBlockDto> blocks = List.of(
                new TimetablePlaceBlockDto(day1, BlockCategory.ATTRACTION, "place-1", "경복궁",
                        BigDecimal.valueOf(4.5), "서울 종로구", BigDecimal.valueOf(37.57), BigDecimal.valueOf(126.97),
                        LocalTime.of(10, 0), LocalTime.of(12, 0), "메모"));

        CreatePlanRequest request = new CreatePlanRequest(planFrame, timetables, blocks);

        CreatePlanResponse response = planService.createPlan(USER_ID, request);

        assertThat(response.planId()).isEqualTo(PLAN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimeTablePlaceBlock>> blockCaptor = ArgumentCaptor.forClass(List.class);
        verify(timeTablePlaceBlockRepository).saveAll(blockCaptor.capture());
        List<TimeTablePlaceBlock> savedBlocks = blockCaptor.getValue();
        assertThat(savedBlocks).hasSize(1);
        assertThat(savedBlocks.get(0).getTimeTable().getDate()).isEqualTo(day1);
        assertThat(savedBlocks.get(0).getPlaceName()).isEqualTo("경복궁");

        ArgumentCaptor<Plan> planCaptor = ArgumentCaptor.forClass(Plan.class);
        verify(planRepository).save(planCaptor.capture());
        assertThat(planCaptor.getValue().isShared()).isTrue();

        verify(planMemberRepository).save(any(PlanMember.class));
    }

    @Test
    @DisplayName("플랜 전체 생성 성공 - timetables/timetablePlaceBlocks가 null이어도 정상 처리(원본 호환)")
    void createPlan_success_withNullLists() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(destination()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
        given(planRepository.save(any(Plan.class))).willReturn(savedPlanStub());
        given(timeTableRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        PlanFrameDto planFrame = new PlanFrameDto(DESTINATION_ID, TransportationType.PRIVATE, 1, 0);
        CreatePlanRequest request = new CreatePlanRequest(planFrame, null, null);

        CreatePlanResponse response = planService.createPlan(USER_ID, request);

        assertThat(response.planId()).isEqualTo(PLAN_ID);
        verify(timeTablePlaceBlockRepository).saveAll(List.of());
        verify(planMemberRepository).save(any(PlanMember.class));
    }

    @Test
    @DisplayName("플랜 전체 생성 실패 - timetables에 중복 날짜")
    void createPlan_fail_duplicateDate() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(destination()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));

        LocalDate day1 = LocalDate.of(2026, 8, 1);
        PlanFrameDto planFrame = new PlanFrameDto(DESTINATION_ID, TransportationType.PRIVATE, 1, 0);
        List<TimetableDto> timetables = List.of(
                new TimetableDto(day1, LocalTime.of(9, 0), LocalTime.of(20, 0)),
                new TimetableDto(day1, LocalTime.of(9, 0), LocalTime.of(20, 0)));

        CreatePlanRequest request = new CreatePlanRequest(planFrame, timetables, null);

        assertThatThrownBy(() -> planService.createPlan(USER_ID, request))
                .isInstanceOf(PlanDuplicateDateException.class);

        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("플랜 전체 생성 실패 - timetablePlaceBlocks 내 동일 (날짜,시작시간) 중복")
    void createPlan_fail_duplicateBlockTime() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(destination()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));

        LocalDate day1 = LocalDate.of(2026, 8, 1);
        PlanFrameDto planFrame = new PlanFrameDto(DESTINATION_ID, TransportationType.PRIVATE, 1, 0);
        List<TimetableDto> timetables = List.of(new TimetableDto(day1, LocalTime.of(9, 0), LocalTime.of(20, 0)));
        List<TimetablePlaceBlockDto> blocks = List.of(
                new TimetablePlaceBlockDto(day1, BlockCategory.ATTRACTION, "p1", "장소1", null, null, null, null,
                        LocalTime.of(10, 0), LocalTime.of(11, 0), null),
                new TimetablePlaceBlockDto(day1, BlockCategory.RESTAURANT, "p2", "장소2", null, null, null, null,
                        LocalTime.of(10, 0), LocalTime.of(11, 30), null));

        CreatePlanRequest request = new CreatePlanRequest(planFrame, timetables, blocks);

        assertThatThrownBy(() -> planService.createPlan(USER_ID, request))
                .isInstanceOf(PlanDuplicateBlockTimeException.class);

        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("플랜 전체 생성 실패 - 블록의 날짜가 timetables 목록에 없음")
    void createPlan_fail_invalidBlockDate() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(destination()));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));

        LocalDate day1 = LocalDate.of(2026, 8, 1);
        LocalDate day2 = LocalDate.of(2026, 8, 2);
        PlanFrameDto planFrame = new PlanFrameDto(DESTINATION_ID, TransportationType.PRIVATE, 1, 0);
        List<TimetableDto> timetables = List.of(new TimetableDto(day1, LocalTime.of(9, 0), LocalTime.of(20, 0)));
        List<TimetablePlaceBlockDto> blocks = List.of(
                new TimetablePlaceBlockDto(day2, BlockCategory.ATTRACTION, "p1", "장소1", null, null, null, null,
                        LocalTime.of(10, 0), LocalTime.of(11, 0), null));

        CreatePlanRequest request = new CreatePlanRequest(planFrame, timetables, blocks);

        assertThatThrownBy(() -> planService.createPlan(USER_ID, request))
                .isInstanceOf(PlanInvalidBlockDateException.class);

        verify(planRepository, never()).save(any());
    }

    @Test
    @DisplayName("플랜 전체 생성 실패 - 목적지 없음")
    void createPlan_fail_destinationNotFound() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.empty());

        PlanFrameDto planFrame = new PlanFrameDto(DESTINATION_ID, TransportationType.PRIVATE, 1, 0);
        CreatePlanRequest request = new CreatePlanRequest(planFrame, null, null);

        assertThatThrownBy(() -> planService.createPlan(USER_ID, request))
                .isInstanceOf(DestinationNotFoundException.class);

        verify(planRepository, never()).save(any());
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
        given(timeTableRepository.findByPlanPlanIdOrderByDate(PLAN_ID)).willReturn(List.of(timeTable));

        TimeTablePlaceBlock block = TimeTablePlaceBlock.builder()
                .blockId(10L)
                .timeTable(timeTable)
                .placeName("경복궁")
                .blockCategory(BlockCategory.ATTRACTION)
                .blockStartTime(LocalTime.of(10, 0))
                .blockEndTime(LocalTime.of(12, 0))
                .build();
        given(timeTablePlaceBlockRepository.findByPlanIdOrderByDateAndStartTime(PLAN_ID)).willReturn(List.of(block));

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
        given(timeTableRepository.findByPlanPlanIdOrderByDate(PLAN_ID)).willReturn(List.of());
        given(timeTablePlaceBlockRepository.findByPlanIdOrderByDateAndStartTime(PLAN_ID)).willReturn(List.of());

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
        given(timeTableRepository.findByPlanPlanIdOrderByDate(PLAN_ID)).willReturn(List.of());
        given(timeTablePlaceBlockRepository.findByPlanIdOrderByDateAndStartTime(PLAN_ID)).willReturn(List.of());

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

    private Plan sharedPlanStub(boolean isShared) {
        return Plan.builder()
                .planId(PLAN_ID)
                .planName("서울 여행")
                .adultCount(1)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(isShared)
                .destination(destination())
                .build();
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

    // ---------- deleteMultiplePlans (#30) ----------

    @Test
    @DisplayName("플랜 다중 삭제 성공 - 소유한 플랜만 삭제되고 deletedPlanIds로 반환")
    void deleteMultiplePlans_success() {
        Plan ownedPlan = Plan.builder().planId(PLAN_ID).build();
        PlanMember ownedMembership = PlanMember.builder().plan(ownedPlan).user(user()).role(PlanMemberRole.OWNER).build();
        given(planMemberRepository.findByUserUserIdAndRoleAndPlanPlanIdIn(
                USER_ID, PlanMemberRole.OWNER, List.of(PLAN_ID, PLAN_ID_2)))
                .willReturn(List.of(ownedMembership));

        DeleteMultiplePlansResponse response = planService.deleteMultiplePlans(USER_ID, List.of(PLAN_ID, PLAN_ID_2));

        assertThat(response.deletedPlanIds()).containsExactly(PLAN_ID);
        assertThat(ownedPlan.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("플랜 다중 삭제 실패 - 소유한 플랜이 하나도 없으면 403")
    void deleteMultiplePlans_fail_noneOwned() {
        given(planMemberRepository.findByUserUserIdAndRoleAndPlanPlanIdIn(
                USER_ID, PlanMemberRole.OWNER, List.of(PLAN_ID)))
                .willReturn(List.of());

        assertThatThrownBy(() -> planService.deleteMultiplePlans(USER_ID, List.of(PLAN_ID)))
                .isInstanceOf(PlanAccessDeniedException.class);
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
