package com.planmate.domain.plan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.dto.CreatePlanFrameRequest;
import com.planmate.domain.plan.dto.CreatePlanRequest;
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
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlanControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private DestinationRepository destinationRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanMemberRepository planMemberRepository;
    @Autowired
    private TimeTableRepository timeTableRepository;
    @Autowired
    private TimeTablePlaceBlockRepository timeTablePlaceBlockRepository;

    private User testUser;
    private String accessToken;
    private Destination destination;

    @BeforeEach
    void setUp() {
        testUser = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("plan-test-" + UUID.randomUUID() + "@example.com")
                .nickname("plan-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        accessToken = jwtTokenProvider.createAccessToken(testUser.getUserId(), testUser.getRole().name());
        destination = destinationRepository.findByDestinationName("서울").orElseThrow();
    }

    // ---------- #24 createPlanFrame ----------

    @Test
    @DisplayName("플랜 프레임 생성 성공 - 201, Plan/TimeTable/PlanMember(OWNER) 실제 DB 반영 확인")
    void createPlanFrame_success() throws Exception {
        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                destination.getDestinationId(), TransportationType.PUBLIC,
                List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2)), 2, 1);

        String responseBody = mockMvc.perform(post("/api/plan")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planId").exists())
                .andReturn().getResponse().getContentAsString();

        UUID planId = UUID.fromString(objectMapper.readTree(responseBody).get("planId").asText());

        Plan savedPlan = planRepository.findById(planId).orElseThrow();
        assertThat(savedPlan.getPlanName()).isEqualTo("서울");
        assertThat(savedPlan.isShared()).isTrue();
        assertThat(savedPlan.getTransportationType()).isEqualTo(TransportationType.PUBLIC);

        List<TimeTable> timeTables = timeTableRepository.findByPlanPlanIdOrderByDate(planId);
        assertThat(timeTables).hasSize(2);
        assertThat(timeTables).extracting(TimeTable::getDate)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));
        assertThat(timeTables).allSatisfy(tt -> {
            assertThat(tt.getTimeTableStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(tt.getTimeTableEndTime()).isEqualTo(LocalTime.of(20, 0));
        });

        PlanMember owner = planMemberRepository.findByPlanPlanIdAndUserUserId(planId, testUser.getUserId())
                .orElseThrow();
        assertThat(owner.getRole()).isEqualTo(PlanMemberRole.OWNER);
    }

    @Test
    @DisplayName("플랜 프레임 생성 실패 - 인증 토큰 없으면 401")
    void createPlanFrame_fail_noAuth() throws Exception {
        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                destination.getDestinationId(), TransportationType.PUBLIC,
                List.of(LocalDate.of(2026, 9, 1)), 1, 0);

        mockMvc.perform(post("/api/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("플랜 프레임 생성 검증 실패 - dates가 빈 리스트면 400, COMMON_001")
    void createPlanFrame_validationFailure_emptyDates() throws Exception {
        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                destination.getDestinationId(), TransportationType.PUBLIC, List.of(), 1, 0);

        mockMvc.perform(post("/api/plan")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("플랜 프레임 생성 실패 - dates에 중복 날짜 있으면 400, PLAN_003")
    void createPlanFrame_fail_duplicateDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 1);
        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                destination.getDestinationId(), TransportationType.PUBLIC, List.of(date, date), 1, 0);

        mockMvc.perform(post("/api/plan")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_003"));
    }

    @Test
    @DisplayName("플랜 프레임 생성 실패 - 존재하지 않는 destinationId면 404, DEST_001")
    void createPlanFrame_fail_destinationNotFound() throws Exception {
        CreatePlanFrameRequest request = new CreatePlanFrameRequest(
                Integer.MAX_VALUE, TransportationType.PUBLIC, List.of(LocalDate.of(2026, 9, 1)), 1, 0);

        mockMvc.perform(post("/api/plan")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEST_001"));
    }

    // ---------- #29 createPlan ----------

    @Test
    @DisplayName("플랜 전체 생성 성공 - 201, 블록이 date로 정확한 TimeTable에 연결되어 저장됨")
    void createPlan_success() throws Exception {
        LocalDate day1 = LocalDate.of(2026, 9, 10);
        LocalDate day2 = LocalDate.of(2026, 9, 11);
        PlanFrameDto planFrame = new PlanFrameDto(destination.getDestinationId(), TransportationType.PRIVATE, 2, 0);
        List<TimetableDto> timetables = List.of(
                new TimetableDto(day1, LocalTime.of(9, 0), LocalTime.of(20, 0)),
                new TimetableDto(day2, LocalTime.of(9, 0), LocalTime.of(20, 0)));
        List<TimetablePlaceBlockDto> blocks = List.of(
                new TimetablePlaceBlockDto(day2, BlockCategory.ATTRACTION, "place-1", "경복궁",
                        null, "서울 종로구", null, null, LocalTime.of(10, 0), LocalTime.of(12, 0), "메모"));
        CreatePlanRequest request = new CreatePlanRequest(planFrame, timetables, blocks);

        String responseBody = mockMvc.perform(post("/api/plan/full")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planId").exists())
                .andReturn().getResponse().getContentAsString();

        UUID planId = UUID.fromString(objectMapper.readTree(responseBody).get("planId").asText());

        List<TimeTable> savedTimeTables = timeTableRepository.findByPlanPlanIdOrderByDate(planId);
        assertThat(savedTimeTables).hasSize(2);
        TimeTable timeTableDay2 = savedTimeTables.stream().filter(tt -> tt.getDate().equals(day2)).findFirst()
                .orElseThrow();
        TimeTable timeTableDay1 = savedTimeTables.stream().filter(tt -> tt.getDate().equals(day1)).findFirst()
                .orElseThrow();

        List<TimeTablePlaceBlock> day2Blocks = timeTablePlaceBlockRepository
                .findByTimeTableTimeTableIdOrderByBlockStartTime(timeTableDay2.getTimeTableId());
        assertThat(day2Blocks).hasSize(1);
        assertThat(day2Blocks.get(0).getPlaceName()).isEqualTo("경복궁");

        assertThat(timeTablePlaceBlockRepository
                .findByTimeTableTimeTableIdOrderByBlockStartTime(timeTableDay1.getTimeTableId())).isEmpty();

        PlanMember owner = planMemberRepository.findByPlanPlanIdAndUserUserId(planId, testUser.getUserId())
                .orElseThrow();
        assertThat(owner.getRole()).isEqualTo(PlanMemberRole.OWNER);
        assertThat(planRepository.findById(planId).orElseThrow().isShared()).isTrue();
    }

    @Test
    @DisplayName("플랜 전체 생성 성공 - timetables/timetablePlaceBlocks 없이도 프레임만 생성됨(원본 호환)")
    void createPlan_success_frameOnly() throws Exception {
        PlanFrameDto planFrame = new PlanFrameDto(destination.getDestinationId(), TransportationType.PRIVATE, 1, 0);
        CreatePlanRequest request = new CreatePlanRequest(planFrame, null, null);

        mockMvc.perform(post("/api/plan/full")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planId").exists());
    }

    @Test
    @DisplayName("플랜 전체 생성 실패 - 블록 시작 시간이 같은 날짜에 중복되면 400, PLAN_004")
    void createPlan_fail_duplicateBlockTime() throws Exception {
        LocalDate day1 = LocalDate.of(2026, 9, 20);
        PlanFrameDto planFrame = new PlanFrameDto(destination.getDestinationId(), TransportationType.PRIVATE, 1, 0);
        List<TimetableDto> timetables = List.of(new TimetableDto(day1, LocalTime.of(9, 0), LocalTime.of(20, 0)));
        List<TimetablePlaceBlockDto> blocks = List.of(
                new TimetablePlaceBlockDto(day1, BlockCategory.ATTRACTION, "p1", "장소1", null, null, null, null,
                        LocalTime.of(10, 0), LocalTime.of(11, 0), null),
                new TimetablePlaceBlockDto(day1, BlockCategory.RESTAURANT, "p2", "장소2", null, null, null, null,
                        LocalTime.of(10, 0), LocalTime.of(11, 30), null));
        CreatePlanRequest request = new CreatePlanRequest(planFrame, timetables, blocks);

        mockMvc.perform(post("/api/plan/full")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_004"));
    }

    @Test
    @DisplayName("플랜 전체 생성 실패 - 블록의 날짜가 timetables 목록에 없으면 400, PLAN_005")
    void createPlan_fail_invalidBlockDate() throws Exception {
        LocalDate day1 = LocalDate.of(2026, 9, 21);
        LocalDate day2 = LocalDate.of(2026, 9, 22);
        PlanFrameDto planFrame = new PlanFrameDto(destination.getDestinationId(), TransportationType.PRIVATE, 1, 0);
        List<TimetableDto> timetables = List.of(new TimetableDto(day1, LocalTime.of(9, 0), LocalTime.of(20, 0)));
        List<TimetablePlaceBlockDto> blocks = List.of(
                new TimetablePlaceBlockDto(day2, BlockCategory.ATTRACTION, "p1", "장소1", null, null, null, null,
                        LocalTime.of(10, 0), LocalTime.of(11, 0), null));
        CreatePlanRequest request = new CreatePlanRequest(planFrame, timetables, blocks);

        mockMvc.perform(post("/api/plan/full")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_005"));
    }
}
