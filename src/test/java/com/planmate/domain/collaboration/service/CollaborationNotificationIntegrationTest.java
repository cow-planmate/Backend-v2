package com.planmate.domain.collaboration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.fcm.FcmSendResult;
import com.planmate.common.fcm.FcmService;
import com.planmate.common.sse.SseEmitterService;
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
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Service 단위 테스트(CollaborationRequestServiceTest)는 "이벤트를 올바르게 발행하는가"만 Mockito로 검증한다.
 * 이 테스트는 그 이벤트가 실제 커밋을 거쳐 NotificationEventListener까지 살아서 FCM/SSE 호출로 이어지는지,
 * 진짜 HTTP 요청 → 실제 Security 인가 → 실제 DB 커밋 → AFTER_COMMIT 리스너까지 전 구간을 통째로 검증한다.
 * 클래스 레벨 @Transactional을 일부러 안 쓴다 — 붙이면 테스트 트랜잭션이 롤백되어 AFTER_COMMIT 리스너가
 * 영원히 안 불린다(설계 문서에서 반복적으로 경고한 함정과 동일).
 * 처음엔 "메커니즘이 트리거를 안 가리니 하나만 대표로 검증하면 충분하다"고 판단했으나, 사용자가
 * "새 알림도 실제로 작동하는지 제대로 테스트됐냐"고 재확인해 4개 트리거 전부 실제 HTTP→커밋→리스너→
 * FCM/SSE 전 구간을 각각 실측하도록 보강했다 — 추론이 아니라 4번 다 직접 관찰한 결과다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CollaborationNotificationIntegrationTest {

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

    @MockitoBean
    private FcmService fcmService;
    @MockitoBean
    private SseEmitterService sseEmitterService;

    private User owner;
    private User receiver;
    private String ownerToken;
    private String receiverToken;
    private Plan plan;

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("notif-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("nf-o-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        receiver = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("notif-receiver-" + UUID.randomUUID() + "@example.com")
                .nickname("nf-r-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());
        receiverToken = jwtTokenProvider.createAccessToken(receiver.getUserId(), receiver.getRole().name());

        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();
        plan = planRepository.saveAndFlush(Plan.builder()
                .planName("알림 연결 테스트 플랜")
                .adultCount(1)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(true)
                .user(owner)
                .destination(destination)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(owner)
                .role(PlanMemberRole.OWNER)
                .build());
    }

    @AfterEach
    void tearDown() {
        collaborationRequestRepository.deleteAll(collaborationRequestRepository.findAll().stream()
                .filter(r -> r.getPlan().getPlanId().equals(plan.getPlanId())).toList());
        planMemberRepository.deleteAll(planMemberRepository.findAll().stream()
                .filter(m -> m.getPlan().getPlanId().equals(plan.getPlanId())).toList());
        planRepository.delete(plan);
        userRepository.delete(owner);
        userRepository.delete(receiver);
    }

    @Test
    @DisplayName("초대 API 호출 → 실제 커밋 → NotificationEventListener → FCM+SSE 실제 호출까지 전 구간 실측")
    void inviteUserToPlan_realCommit_triggersFcmAndSse() throws Exception {
        given(fcmService.sendNotification(any(), any(), any())).willReturn(FcmSendResult.SKIPPED_NO_TOKEN);

        mockMvc.perform(post("/api/plan/{planId}/invite", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserToPlanRequest(receiver.getNickname()))))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sseEmitterService, timeout(3000)).sendNotification(
                eq(receiver.getUserId()), eq("invitation"), dataCaptor.capture());
        assertThat(dataCaptor.getValue())
                .containsEntry("type", "INVITE")
                .containsEntry("senderNickname", owner.getNickname())
                .containsEntry("planName", plan.getPlanName())
                .containsKey("collaborationRequestId");

        verify(fcmService, timeout(3000)).sendNotification(
                isNull(),
                eq("일정 초대"),
                eq(owner.getNickname() + "님이 '" + plan.getPlanName() + "' 일정에 초대했습니다."));
    }

    @Test
    @DisplayName("편집 권한 요청 API 호출 → 실제 커밋 → NotificationEventListener → FCM+SSE 실제 호출까지 전 구간 실측")
    void requestEditAccess_realCommit_triggersFcmAndSse() throws Exception {
        given(fcmService.sendNotification(any(), any(), any())).willReturn(FcmSendResult.SKIPPED_NO_TOKEN);

        mockMvc.perform(post("/api/plan/{planId}/request-access", plan.getPlanId())
                        .header("Authorization", "Bearer " + receiverToken))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sseEmitterService, timeout(3000)).sendNotification(
                eq(owner.getUserId()), eq("invitation"), dataCaptor.capture());
        assertThat(dataCaptor.getValue())
                .containsEntry("type", "REQUEST")
                .containsEntry("senderNickname", receiver.getNickname())
                .containsEntry("planName", plan.getPlanName())
                .containsKey("collaborationRequestId");

        verify(fcmService, timeout(3000)).sendNotification(
                isNull(),
                eq("편집 권한 요청"),
                eq(receiver.getNickname() + "님이 '" + plan.getPlanName() + "' 일정의 편집 권한을 요청했습니다."));
    }

    @Test
    @DisplayName("요청 수락 API 호출 → 실제 커밋 → NotificationEventListener → FCM+SSE 실제 호출까지 전 구간 실측")
    void acceptRequest_realCommit_triggersFcmAndSse() throws Exception {
        given(fcmService.sendNotification(any(), any(), any())).willReturn(FcmSendResult.SKIPPED_NO_TOKEN);

        // receiver가 owner의 플랜에 편집 권한을 요청(REQUEST)해둔 상태 — owner가 이 요청의 receiver라 수락 권한이 있음
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.REQUEST)
                .status(CollaborationRequestStatus.PENDING)
                .sender(receiver)
                .receiver(owner)
                .plan(plan)
                .build());

        mockMvc.perform(post("/api/collaboration-requests/{collaborationRequestId}/accept", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // 알림 수신자는 request.getSender()(=receiver, 응답을 기다리던 원래 요청자) — API 호출자(owner)가 아님
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sseEmitterService, timeout(3000)).sendNotification(
                eq(receiver.getUserId()), eq("requestResult"), dataCaptor.capture());
        assertThat(dataCaptor.getValue())
                .containsEntry("result", "ACCEPTED")
                .containsEntry("receiverNickname", owner.getNickname())
                .containsEntry("planName", plan.getPlanName());

        verify(fcmService, timeout(3000)).sendNotification(
                isNull(),
                eq("요청 수락"),
                eq(owner.getNickname() + "님이 '" + plan.getPlanName() + "' 관련 요청을 수락했습니다."));
    }

    @Test
    @DisplayName("요청 거절 API 호출 → 실제 커밋 → NotificationEventListener → FCM+SSE 실제 호출까지 전 구간 실측")
    void rejectRequest_realCommit_triggersFcmAndSse() throws Exception {
        given(fcmService.sendNotification(any(), any(), any())).willReturn(FcmSendResult.SKIPPED_NO_TOKEN);

        // owner가 receiver를 초대(INVITE)해둔 상태 — receiver가 이 요청의 receiver라 거절 권한이 있음
        CollaborationRequest request = collaborationRequestRepository.saveAndFlush(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(owner)
                .receiver(receiver)
                .plan(plan)
                .build());

        mockMvc.perform(post("/api/collaboration-requests/{collaborationRequestId}/reject", request.getCollaborationRequestId())
                        .header("Authorization", "Bearer " + receiverToken))
                .andExpect(status().isOk());

        // 알림 수신자는 request.getSender()(=owner, 응답을 기다리던 원래 초대자) — API 호출자(receiver)가 아님
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sseEmitterService, timeout(3000)).sendNotification(
                eq(owner.getUserId()), eq("requestResult"), dataCaptor.capture());
        assertThat(dataCaptor.getValue())
                .containsEntry("result", "REJECTED")
                .containsEntry("receiverNickname", receiver.getNickname())
                .containsEntry("planName", plan.getPlanName());

        verify(fcmService, timeout(3000)).sendNotification(
                isNull(),
                eq("요청 거절"),
                eq(receiver.getNickname() + "님이 '" + plan.getPlanName() + "' 관련 요청을 거절했습니다."));
    }
}
