package com.planmate.domain.plan.service;

import com.planmate.common.exception.domain.DestinationNotFoundException;
import com.planmate.common.exception.domain.PlanDuplicateBlockTimeException;
import com.planmate.common.exception.domain.PlanDuplicateDateException;
import com.planmate.common.exception.domain.PlanInvalidBlockDateException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.dto.CreatePlanFrameRequest;
import com.planmate.domain.plan.dto.CreatePlanFrameResponse;
import com.planmate.domain.plan.dto.CreatePlanRequest;
import com.planmate.domain.plan.dto.CreatePlanResponse;
import com.planmate.domain.plan.dto.PlanFrameDto;
import com.planmate.domain.plan.dto.TimetableDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDto;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
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
}
