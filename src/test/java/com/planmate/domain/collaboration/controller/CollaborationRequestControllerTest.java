package com.planmate.domain.collaboration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.collaboration.dto.InviteUserToPlanRequest;
import com.planmate.domain.collaboration.entity.CollaborationRequest;
import com.planmate.domain.collaboration.enums.CollaborationRequestStatus;
import com.planmate.domain.collaboration.enums.CollaborationRequestType;
import com.planmate.domain.collaboration.repository.CollaborationRequestRepository;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CollaborationRequestControllerTest {

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
    private CollaborationRequestRepository collaborationRequestRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private String ownerToken;
    private Destination destination;

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("collab-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("c-owner-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());
        destination = destinationRepository.findByDestinationName("서울").orElseThrow();
    }

    // ---------- #39 getReceivedPendingRequests ----------

    @Test
    @DisplayName("받은 대기 요청 목록 조회 성공 - 200, INVITE/REQUEST 섞여서 반환")
    void getReceivedPendingRequests_success() throws Exception {
        Plan plan = createTestPlan(owner);
        User other = createOtherUser();
        collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.REQUEST)
                .status(CollaborationRequestStatus.PENDING)
                .sender(other)
                .receiver(owner)
                .plan(plan)
                .build());

        mockMvc.perform(get("/api/collaboration-requests/pending")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].senderId").value(other.getUserId().toString()))
                .andExpect(jsonPath("$.requests[0].type").value("REQUEST"));
    }

    @Test
    @DisplayName("받은 대기 요청 목록 조회 성공 - 대기 중인 요청 없으면 빈 리스트")
    void getReceivedPendingRequests_success_empty() throws Exception {
        mockMvc.perform(get("/api/collaboration-requests/pending")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    @Test
    @DisplayName("받은 대기 요청 목록 조회 실패 - 인증 토큰 없으면 401")
    void getReceivedPendingRequests_fail_noAuth() throws Exception {
        mockMvc.perform(get("/api/collaboration-requests/pending"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("받은 대기 요청 목록 조회 - 플랜이 소프트 삭제되면 그 요청은 목록에서 제외됨(B_요청_처리.md 10-1절 실측을 회귀 테스트로 승격)")
    void getReceivedPendingRequests_excludesRequestsWithSoftDeletedPlan() throws Exception {
        Plan plan = createTestPlan(owner);
        User other = createOtherUser();
        collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.REQUEST)
                .status(CollaborationRequestStatus.PENDING)
                .sender(other)
                .receiver(owner)
                .plan(plan)
                .build());

        plan.softDelete();
        planRepository.saveAndFlush(plan);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/collaboration-requests/pending")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    // ---------- #40 acceptRequest ----------

    @Test
    @DisplayName("요청 수락 성공 - INVITE: 받는 사람이 새 편집자로 추가되고 200 + planId 반환")
    void acceptRequest_success_inviteType() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());
        String inviteeToken = jwtTokenProvider.createAccessToken(invitee.getUserId(), invitee.getRole().name());

        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(plan.getPlanId().toString()));

        entityManager.flush();
        entityManager.clear();
        PlanMember member = planMemberRepository.findByPlanPlanIdAndUserUserId(plan.getPlanId(), invitee.getUserId())
                .orElseThrow();
        assertThat(member.getRole()).isEqualTo(PlanMemberRole.EDITOR);
        assertThat(member.getInvitedBy().getUserId()).isEqualTo(owner.getUserId());
        assertThat(collaborationRequestRepository.findById(request.getCollaborationRequestId()).orElseThrow().getStatus())
                .isEqualTo(CollaborationRequestStatus.ACCEPTED);
    }

    @Test
    @DisplayName("요청 수락 성공 - REQUEST: 보낸 사람이 새 편집자로 추가되고 invited_by=오너")
    void acceptRequest_success_requestType() throws Exception {
        Plan plan = createTestPlan(owner);
        User requester = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.REQUEST)
                .status(CollaborationRequestStatus.PENDING)
                .sender(requester)
                .receiver(owner)
                .plan(plan)
                .build());

        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(plan.getPlanId().toString()));

        entityManager.flush();
        entityManager.clear();
        PlanMember member = planMemberRepository.findByPlanPlanIdAndUserUserId(plan.getPlanId(), requester.getUserId())
                .orElseThrow();
        assertThat(member.getInvitedBy().getUserId()).isEqualTo(owner.getUserId());
    }

    @Test
    @DisplayName("요청 수락 실패 - 인증 토큰 없으면 401")
    void acceptRequest_fail_noAuth() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());

        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", request.getCollaborationRequestId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("요청 수락 실패 - 이 요청의 수신자가 아니면 403, COLLAB_004")
    void acceptRequest_fail_notReceiver() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        User stranger = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());
        String strangerToken = jwtTokenProvider.createAccessToken(stranger.getUserId(), stranger.getRole().name());

        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COLLAB_004"));
    }

    @Test
    @DisplayName("요청 수락 실패 - 존재하지 않는 요청이면 404, COLLAB_001")
    void acceptRequest_fail_requestNotFound() throws Exception {
        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", 999999L)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLLAB_001"));
    }

    @Test
    @DisplayName("요청 수락 실패 - 이미 처리된 요청이면 409, COLLAB_005")
    void acceptRequest_fail_alreadyProcessed() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.ACCEPTED)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());
        String inviteeToken = jwtTokenProvider.createAccessToken(invitee.getUserId(), invitee.getRole().name());

        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLAB_005"));
    }

    @Test
    @DisplayName("요청 수락 실패 - 연결된 플랜이 그사이 소프트 삭제되면 404, PLAN_001(B_요청_처리.md 10-2절)")
    void acceptRequest_fail_planSoftDeleted() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());
        String inviteeToken = jwtTokenProvider.createAccessToken(invitee.getUserId(), invitee.getRole().name());

        plan.softDelete();
        planRepository.saveAndFlush(plan);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_001"));
    }

    @Test
    @DisplayName("요청 수락 실패 - 새 편집자가 될 대상이 그사이 탈퇴(소프트 삭제)하면 404, USER_001(17장 도메인 전체 점검에서 발견 — 유령 plan_member 생성 방지)")
    void acceptRequest_fail_targetUserWithdrawn() throws Exception {
        Plan plan = createTestPlan(owner);
        User requester = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.REQUEST)
                .status(CollaborationRequestStatus.PENDING)
                .sender(requester)
                .receiver(owner)
                .plan(plan)
                .build());

        // 요청을 보낸 사람(수락 시 새 편집자가 될 사람)이 그 사이 탈퇴
        requester.softDelete();
        userRepository.saveAndFlush(requester);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_001"));

        // 유령 plan_member 행이 생기지 않았는지 raw SQL로 직접 확인(JPA 경로는 User 조인에
        // @SQLRestriction이 적용돼 실제로 행이 생겨도 안 보일 수 있어 이 케이스에선 신뢰할 수 없음)
        Integer rawCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plan_member WHERE plan_id = ? AND user_id = ?",
                Integer.class, plan.getPlanId(), requester.getUserId());
        assertThat(rawCount).isZero();
        assertThat(collaborationRequestRepository.findById(request.getCollaborationRequestId()).orElseThrow().getStatus())
                .isEqualTo(CollaborationRequestStatus.PENDING);
    }

    // ---------- #41 rejectRequest ----------

    @Test
    @DisplayName("요청 거절 성공 - 200, DB 상태 REJECTED로 반영, 멤버십 변경 없음")
    void rejectRequest_success() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());
        String inviteeToken = jwtTokenProvider.createAccessToken(invitee.getUserId(), invitee.getRole().name());

        mockMvc.perform(post("/api/collaboration-requests/{id}/reject", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collaborationRequestId").value(request.getCollaborationRequestId()));

        entityManager.flush();
        entityManager.clear();
        assertThat(collaborationRequestRepository.findById(request.getCollaborationRequestId()).orElseThrow().getStatus())
                .isEqualTo(CollaborationRequestStatus.REJECTED);
        assertThat(planMemberRepository.findByPlanPlanIdAndUserUserId(plan.getPlanId(), invitee.getUserId())).isEmpty();
    }

    @Test
    @DisplayName("요청 거절 실패 - 인증 토큰 없으면 401")
    void rejectRequest_fail_noAuth() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());

        mockMvc.perform(post("/api/collaboration-requests/{id}/reject", request.getCollaborationRequestId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("요청 거절 실패 - 이 요청의 수신자가 아니면 403, COLLAB_004")
    void rejectRequest_fail_notReceiver() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        User stranger = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());
        String strangerToken = jwtTokenProvider.createAccessToken(stranger.getUserId(), stranger.getRole().name());

        mockMvc.perform(post("/api/collaboration-requests/{id}/reject", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COLLAB_004"));
    }

    @Test
    @DisplayName("요청 거절 실패 - 존재하지 않는 요청이면 404, COLLAB_001")
    void rejectRequest_fail_requestNotFound() throws Exception {
        mockMvc.perform(post("/api/collaboration-requests/{id}/reject", 999999L)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLLAB_001"));
    }

    @Test
    @DisplayName("요청 거절 실패 - 이미 처리된 요청이면 409, COLLAB_005")
    void rejectRequest_fail_alreadyProcessed() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.REJECTED)
                .sender(owner)
                .receiver(invitee)
                .plan(plan)
                .build());
        String inviteeToken = jwtTokenProvider.createAccessToken(invitee.getUserId(), invitee.getRole().name());

        mockMvc.perform(post("/api/collaboration-requests/{id}/reject", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLAB_005"));
    }

    // ---------- A그룹(요청 생성) → B그룹(요청 처리) 전체 여정 실측 (실제 HTTP 흐름 이어붙이기) ----------

    @Test
    @DisplayName("전체 여정 - 초대 API 호출 → 받은 대기 목록에 노출 → 수락 API 호출 → 편집자로 추가되고 목록에서 사라짐")
    void fullJourney_invite_thenListed_thenAccepted() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        String inviteeToken = jwtTokenProvider.createAccessToken(invitee.getUserId(), invitee.getRole().name());

        // 1. 오너가 실제 초대 API를 호출
        String inviteResponseBody = mockMvc.perform(post("/api/plan/{planId}/invite", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserToPlanRequest(invitee.getNickname()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long requestId = objectMapper.readTree(inviteResponseBody).get("collaborationRequestId").asLong();

        // 2. 초대받은 사람이 실제 목록 조회 API로 확인
        mockMvc.perform(get("/api/collaboration-requests/pending")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].collaborationRequestId").value(requestId))
                .andExpect(jsonPath("$.requests[0].senderNickname").value(owner.getNickname()))
                .andExpect(jsonPath("$.requests[0].type").value("INVITE"));

        // 3. 초대받은 사람이 실제 수락 API를 호출
        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", requestId)
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(plan.getPlanId().toString()));

        // 4. 실제로 편집자가 됐는지, 목록에서 사라졌는지 재확인
        entityManager.flush();
        entityManager.clear();
        PlanMember member = planMemberRepository.findByPlanPlanIdAndUserUserId(plan.getPlanId(), invitee.getUserId())
                .orElseThrow();
        assertThat(member.getRole()).isEqualTo(PlanMemberRole.EDITOR);
        assertThat(member.getInvitedBy().getUserId()).isEqualTo(owner.getUserId());

        mockMvc.perform(get("/api/collaboration-requests/pending")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    @Test
    @DisplayName("전체 여정 - 편집 권한 요청 API 호출 → 오너 대기 목록에 노출 → 거절 API 호출 → 편집자로 추가 안 되고 목록에서 사라짐")
    void fullJourney_requestAccess_thenListed_thenRejected() throws Exception {
        Plan plan = createTestPlan(owner);
        User requester = createOtherUser();
        String requesterToken = jwtTokenProvider.createAccessToken(requester.getUserId(), requester.getRole().name());

        // 1. 요청자가 실제 편집 권한 요청 API를 호출
        String requestResponseBody = mockMvc.perform(post("/api/plan/{planId}/request-access", plan.getPlanId())
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long requestId = objectMapper.readTree(requestResponseBody).get("collaborationRequestId").asLong();

        // 2. 오너가 실제 목록 조회 API로 확인
        mockMvc.perform(get("/api/collaboration-requests/pending")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].senderNickname").value(requester.getNickname()))
                .andExpect(jsonPath("$.requests[0].type").value("REQUEST"));

        // 3. 오너가 실제 거절 API를 호출
        mockMvc.perform(post("/api/collaboration-requests/{id}/reject", requestId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collaborationRequestId").value(requestId));

        // 4. 편집자로 추가되지 않았는지, 목록에서 사라졌는지 재확인
        entityManager.flush();
        entityManager.clear();
        assertThat(planMemberRepository.findByPlanPlanIdAndUserUserId(plan.getPlanId(), requester.getUserId())).isEmpty();
        assertThat(collaborationRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(CollaborationRequestStatus.REJECTED);

        mockMvc.perform(get("/api/collaboration-requests/pending")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    @Test
    @DisplayName("전체 여정 - 초대 수락 후 같은 사람을 다시 초대하면 이미 편집자라 409(COLLAB_002) — A/B그룹 교차 확인")
    void fullJourney_afterAccept_reInviteFailsAsAlreadyMember() throws Exception {
        Plan plan = createTestPlan(owner);
        User invitee = createOtherUser();
        String inviteeToken = jwtTokenProvider.createAccessToken(invitee.getUserId(), invitee.getRole().name());

        String inviteResponseBody = mockMvc.perform(post("/api/plan/{planId}/invite", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserToPlanRequest(invitee.getNickname()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long requestId = objectMapper.readTree(inviteResponseBody).get("collaborationRequestId").asLong();

        mockMvc.perform(post("/api/collaboration-requests/{id}/accept", requestId)
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/plan/{planId}/invite", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserToPlanRequest(invitee.getNickname()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLAB_002"));
    }

    private Plan createTestPlan(User planOwner) {
        Plan plan = planRepository.saveAndFlush(Plan.builder()
                .planName("협업요청 테스트 플랜")
                .adultCount(1)
                .childCount(0)
                .isShared(true)
                .user(planOwner)
                .destination(destination)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(planOwner)
                .role(PlanMemberRole.OWNER)
                .build());
        return plan;
    }

    private User createOtherUser() {
        return userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("collab-other-" + UUID.randomUUID() + "@example.com")
                .nickname("c-other-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
    }
}
