package com.planmate.domain.plan.service;

import com.planmate.common.exception.domain.AuthenticationRequiredException;
import com.planmate.common.exception.domain.DestinationNotFoundException;
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
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.dto.GetPlanResponse;
import com.planmate.domain.plan.dto.PlanFrameDetailDto;
import com.planmate.domain.plan.dto.PlanFrameDto;
import com.planmate.domain.plan.dto.TimetableDetailDto;
import com.planmate.domain.plan.dto.TimetableDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDetailDto;
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
import com.planmate.domain.timetable.repository.TimeTablePlaceBlockRepository;
import com.planmate.domain.timetable.repository.TimeTableRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanService {

    private static final LocalTime DEFAULT_START_TIME = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_END_TIME = LocalTime.of(20, 0);

    private final PlanRepository planRepository;
    private final PlanMemberRepository planMemberRepository;
    private final UserRepository userRepository;
    private final DestinationRepository destinationRepository;
    private final TimeTableRepository timeTableRepository;
    private final TimeTablePlaceBlockRepository timeTablePlaceBlockRepository;
    private final PlanAccessValidator planAccessValidator;

    @Transactional
    public CreatePlanFrameResponse createPlanFrame(UUID userId, CreatePlanFrameRequest request) {
        Destination destination = destinationRepository.findById(request.destinationId())
                .orElseThrow(DestinationNotFoundException::new);
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);
        validateNoDuplicateDates(request.dates());

        Plan savedPlan = planRepository.save(buildPlan(
                destination, user, request.transportationType(),
                request.adultCount(), request.childCount()));

        List<TimeTable> timeTables = request.dates().stream()
                .map(date -> TimeTable.builder()
                        .date(date)
                        .timeTableStartTime(DEFAULT_START_TIME)
                        .timeTableEndTime(DEFAULT_END_TIME)
                        .plan(savedPlan)
                        .build())
                .toList();
        timeTableRepository.saveAll(timeTables);

        createOwnerMember(savedPlan, user);

        return new CreatePlanFrameResponse(savedPlan.getPlanId());
    }

    @Transactional
    public CreatePlanResponse createPlan(UUID userId, CreatePlanRequest request) {
        PlanFrameDto planFrame = request.planFrame();
        Destination destination = destinationRepository.findById(planFrame.destinationId())
                .orElseThrow(DestinationNotFoundException::new);
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);

        List<TimetableDto> timetables = request.timetables() == null ? List.of() : request.timetables();
        List<TimetablePlaceBlockDto> blocks = request.timetablePlaceBlocks() == null
                ? List.of() : request.timetablePlaceBlocks();

        validateNoDuplicateDates(timetables.stream().map(TimetableDto::date).toList());
        validateNoDuplicateBlockTimes(blocks);
        validateBlockDatesExistInTimetables(timetables, blocks);

        Plan savedPlan = planRepository.save(buildPlan(
                destination, user, planFrame.transportationType(),
                planFrame.adultCount(), planFrame.childCount()));

        List<TimeTable> savedTimeTables = timeTableRepository.saveAll(timetables.stream()
                .map(timetable -> TimeTable.builder()
                        .date(timetable.date())
                        .timeTableStartTime(timetable.timeTableStartTime())
                        .timeTableEndTime(timetable.timeTableEndTime())
                        .plan(savedPlan)
                        .build())
                .toList());

        Map<LocalDate, TimeTable> timeTableByDate = savedTimeTables.stream()
                .collect(Collectors.toMap(TimeTable::getDate, timeTable -> timeTable));

        List<TimeTablePlaceBlock> placeBlocks = blocks.stream()
                .map(block -> TimeTablePlaceBlock.builder()
                        .timeTable(timeTableByDate.get(block.date()))
                        .placeId(block.placeId())
                        .placeName(block.placeName())
                        .placeRating(block.placeRating())
                        .placeAddress(block.placeAddress())
                        .latitude(block.latitude())
                        .longitude(block.longitude())
                        .blockStartTime(block.blockStartTime())
                        .blockEndTime(block.blockEndTime())
                        .blockCategory(block.blockCategory())
                        .memo(block.memo())
                        .build())
                .toList();
        timeTablePlaceBlockRepository.saveAll(placeBlocks);

        createOwnerMember(savedPlan, user);

        return new CreatePlanResponse(savedPlan.getPlanId());
    }

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional(readOnly = true)
    public GetPlanResponse getPlan(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);

        // TODO(sharedsync): 원본 PlanService.getPlan()은 이 지점에서
        //   1) presenceStorage.waitForSync(planId) 로 실시간 편집 세션과의 동기화를 먼저 기다리고
        //   2) planCache/timeTableCache/timeTablePlaceBlockCache에 최신 상태가 있으면 그걸 우선 사용했다.
        // sharedsync 통합 범위/설계가 아직 미확정이라(전체_작업_진행상황.md 5절 1번) 지금은 DB 직접 조회만 수행한다.
        // sharedsync 통합 착수 시 이 메서드부터 재검토할 것 — B_조회.md 10-1절 참고.
        List<TimetableDetailDto> timetables = timeTableRepository.findByPlanPlanIdOrderByDate(planId).stream()
                .map(TimetableDetailDto::from)
                .toList();
        List<TimetablePlaceBlockDetailDto> placeBlocks = timeTablePlaceBlockRepository
                .findByPlanIdOrderByDateAndStartTime(planId).stream()
                .map(TimetablePlaceBlockDetailDto::from)
                .toList();

        return new GetPlanResponse(PlanFrameDetailDto.from(plan), timetables, placeBlocks);
    }

    @Transactional(readOnly = true)
    public GetCompletePlanResponse getCompletePlan(UUID userId, UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);

        if (!plan.isShared()) {
            if (userId == null) {
                throw new AuthenticationRequiredException();
            }
            planAccessValidator.validateMember(planId, userId);
        }

        List<TimetableDetailDto> timetables = timeTableRepository.findByPlanPlanIdOrderByDate(planId).stream()
                .map(TimetableDetailDto::from)
                .toList();
        List<TimetablePlaceBlockDetailDto> placeBlocks = timeTablePlaceBlockRepository
                .findByPlanIdOrderByDateAndStartTime(planId).stream()
                .map(TimetablePlaceBlockDetailDto::from)
                .toList();

        return new GetCompletePlanResponse(PlanFrameDetailDto.from(plan), timetables, placeBlocks);
    }

    private Plan buildPlan(Destination destination, User user,
            TransportationType transportationType, int adultCount, int childCount) {
        return Plan.builder()
                .planName(destination.getDestinationName())
                .adultCount(adultCount)
                .childCount(childCount)
                .transportationType(transportationType)
                .isShared(true)
                .user(user)
                .destination(destination)
                .build();
    }

    private void createOwnerMember(Plan plan, User user) {
        planMemberRepository.save(PlanMember.builder()
                .plan(plan)
                .user(user)
                .role(PlanMemberRole.OWNER)
                .build());
    }

    private void validateNoDuplicateDates(List<LocalDate> dates) {
        Set<LocalDate> distinct = new HashSet<>(dates);
        if (distinct.size() != dates.size()) {
            throw new PlanDuplicateDateException();
        }
    }

    private void validateNoDuplicateBlockTimes(List<TimetablePlaceBlockDto> blocks) {
        Set<String> seen = new HashSet<>();
        for (TimetablePlaceBlockDto block : blocks) {
            String key = block.date() + "|" + block.blockStartTime();
            if (!seen.add(key)) {
                throw new PlanDuplicateBlockTimeException();
            }
        }
    }

    private void validateBlockDatesExistInTimetables(List<TimetableDto> timetables,
            List<TimetablePlaceBlockDto> blocks) {
        Set<LocalDate> timetableDates = timetables.stream().map(TimetableDto::date).collect(Collectors.toSet());
        boolean hasInvalidBlock = blocks.stream().anyMatch(block -> !timetableDates.contains(block.date()));
        if (hasInvalidBlock) {
            throw new PlanInvalidBlockDateException();
        }
    }
}
