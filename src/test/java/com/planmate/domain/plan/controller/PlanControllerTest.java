package com.planmate.domain.plan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.dto.CreatePlanFrameRequest;
import com.planmate.domain.plan.dto.CreatePlanRequest;
import com.planmate.domain.plan.dto.DeleteMultiplePlansRequest;
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
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import jakarta.persistence.EntityManager;
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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @Autowired
    private EntityManager entityManager;

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

    // ---------- #25 getPlan ----------

    @Test
    @DisplayName("플랜 상세 조회 성공 - 200, 소유자")
    void getPlan_success_owner() throws Exception {
        Plan plan = createTestPlan(true, testUser);

        mockMvc.perform(get("/api/plan/{planId}", plan.getPlanId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planFrame.planId").value(plan.getPlanId().toString()))
                .andExpect(jsonPath("$.planFrame.destinationName").value("서울"));
    }

    @Test
    @DisplayName("플랜 상세 조회 실패 - 인증 토큰 없으면 401")
    void getPlan_fail_noAuth() throws Exception {
        Plan plan = createTestPlan(true, testUser);

        mockMvc.perform(get("/api/plan/{planId}", plan.getPlanId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("플랜 상세 조회 실패 - 로그인은 했지만 소유자/편집자가 아니면 403")
    void getPlan_fail_notMember() throws Exception {
        Plan plan = createTestPlan(true, testUser);
        String otherToken = createOtherUserToken();

        mockMvc.perform(get("/api/plan/{planId}", plan.getPlanId())
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_002"));
    }

    @Test
    @DisplayName("플랜 상세 조회 실패 - 존재하지 않는 플랜이면 404")
    void getPlan_fail_planNotFound() throws Exception {
        mockMvc.perform(get("/api/plan/{planId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_001"));
    }

    // ---------- #26 getCompletePlan (원본 #26/27 통합) ----------

    @Test
    @DisplayName("완료된 플랜 조회 성공 - is_shared=true면 인증 헤더 없이도(익명) 200")
    void getCompletePlan_success_sharedTrue_anonymous() throws Exception {
        Plan plan = createTestPlan(true, testUser);

        mockMvc.perform(get("/api/plan/{planId}/complete", plan.getPlanId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planFrame.planId").value(plan.getPlanId().toString()));
    }

    @Test
    @DisplayName("완료된 플랜 조회 실패 - is_shared=false, 인증 헤더 없으면 401")
    void getCompletePlan_fail_sharedFalse_noAuth() throws Exception {
        Plan plan = createTestPlan(false, testUser);

        mockMvc.perform(get("/api/plan/{planId}/complete", plan.getPlanId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("완료된 플랜 조회 실패 - is_shared=false, 로그인했지만 멤버 아니면 403")
    void getCompletePlan_fail_sharedFalse_notMember() throws Exception {
        Plan plan = createTestPlan(false, testUser);
        String otherToken = createOtherUserToken();

        mockMvc.perform(get("/api/plan/{planId}/complete", plan.getPlanId())
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_002"));
    }

    @Test
    @DisplayName("완료된 플랜 조회 성공 - is_shared=false, 로그인했고 소유자면 200")
    void getCompletePlan_success_sharedFalse_owner() throws Exception {
        Plan plan = createTestPlan(false, testUser);

        mockMvc.perform(get("/api/plan/{planId}/complete", plan.getPlanId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planFrame.planId").value(plan.getPlanId().toString()));
    }

    @Test
    @DisplayName("완료된 플랜 조회 실패 - 존재하지 않는 플랜이면 404(익명 요청이어도)")
    void getCompletePlan_fail_planNotFound() throws Exception {
        mockMvc.perform(get("/api/plan/{planId}/complete", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_001"));
    }

    // ---------- #28 deletePlan ----------

    @Test
    @DisplayName("플랜 삭제 성공 - 204, 소프트 삭제 반영 확인 + 삭제 후 재조회 시 404")
    void deletePlan_success() throws Exception {
        Plan plan = createTestPlan(true, testUser);

        mockMvc.perform(delete("/api/plan/{planId}", plan.getPlanId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();
        assertThat(planRepository.findById(plan.getPlanId())).isEmpty();

        mockMvc.perform(get("/api/plan/{planId}", plan.getPlanId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_001"));
    }

    @Test
    @DisplayName("플랜 삭제 실패 - 인증 토큰 없으면 401")
    void deletePlan_fail_noAuth() throws Exception {
        Plan plan = createTestPlan(true, testUser);

        mockMvc.perform(delete("/api/plan/{planId}", plan.getPlanId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("플랜 삭제 실패 - 편집자(EDITOR)는 소유자가 아니므로 403")
    void deletePlan_fail_editorNotOwner() throws Exception {
        Plan plan = createTestPlan(true, testUser);
        User editor = createOtherUser();
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(editor)
                .role(PlanMemberRole.EDITOR)
                .build());
        String editorToken = jwtTokenProvider.createAccessToken(editor.getUserId(), editor.getRole().name());

        mockMvc.perform(delete("/api/plan/{planId}", plan.getPlanId())
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_002"));
    }

    @Test
    @DisplayName("플랜 삭제 실패 - 존재하지 않는 플랜이면 404")
    void deletePlan_fail_planNotFound() throws Exception {
        mockMvc.perform(delete("/api/plan/{planId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_001"));
    }

    // ---------- #30 deleteMultiplePlans ----------

    @Test
    @DisplayName("플랜 다중 삭제 성공 - 소유한 것만 삭제되고 deletedPlanIds로 반환, 소유하지 않은 건 그대로 남음")
    void deleteMultiplePlans_success() throws Exception {
        Plan owned1 = createTestPlan(true, testUser);
        Plan owned2 = createTestPlan(true, testUser);
        User otherOwner = createOtherUser();
        Plan notOwned = createTestPlan(true, otherOwner);

        DeleteMultiplePlansRequest request = new DeleteMultiplePlansRequest(
                List.of(owned1.getPlanId(), owned2.getPlanId(), notOwned.getPlanId()));

        mockMvc.perform(delete("/api/plan")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedPlanIds.length()").value(2))
                .andExpect(jsonPath("$.deletedPlanIds", containsInAnyOrder(
                        owned1.getPlanId().toString(), owned2.getPlanId().toString())));

        entityManager.flush();
        entityManager.clear();
        assertThat(planRepository.findById(owned1.getPlanId())).isEmpty();
        assertThat(planRepository.findById(owned2.getPlanId())).isEmpty();
        assertThat(planRepository.findById(notOwned.getPlanId())).isPresent();
    }

    @Test
    @DisplayName("플랜 다중 삭제 실패 - planIds가 비어있으면 400")
    void deleteMultiplePlans_fail_emptyList() throws Exception {
        DeleteMultiplePlansRequest request = new DeleteMultiplePlansRequest(List.of());

        mockMvc.perform(delete("/api/plan")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("플랜 다중 삭제 실패 - 소유한 플랜이 하나도 없으면 403")
    void deleteMultiplePlans_fail_noneOwned() throws Exception {
        User otherOwner = createOtherUser();
        Plan notOwned = createTestPlan(true, otherOwner);

        DeleteMultiplePlansRequest request = new DeleteMultiplePlansRequest(List.of(notOwned.getPlanId()));

        mockMvc.perform(delete("/api/plan")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_002"));
    }

    @Test
    @DisplayName("플랜 다중 삭제 실패 - 인증 토큰 없으면 401")
    void deleteMultiplePlans_fail_noAuth() throws Exception {
        DeleteMultiplePlansRequest request = new DeleteMultiplePlansRequest(List.of(UUID.randomUUID()));

        mockMvc.perform(delete("/api/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    private Plan createTestPlan(boolean isShared, User owner) {
        Plan plan = planRepository.saveAndFlush(Plan.builder()
                .planName("테스트 플랜")
                .adultCount(1)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(isShared)
                .user(owner)
                .destination(destination)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(owner)
                .role(PlanMemberRole.OWNER)
                .build());
        return plan;
    }

    private String createOtherUserToken() {
        User otherUser = createOtherUser();
        return jwtTokenProvider.createAccessToken(otherUser.getUserId(), otherUser.getRole().name());
    }

    private User createOtherUser() {
        return userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("plan-test-other-" + UUID.randomUUID() + "@example.com")
                .nickname("other-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
    }
}
