package com.planmate.domain.collaboration.service;

import com.planmate.common.exception.domain.CollaborationRequestAlreadyMemberException;
import com.planmate.common.exception.domain.CollaborationRequestAlreadyProcessedException;
import com.planmate.common.exception.domain.CollaborationRequestDuplicatePendingException;
import com.planmate.common.exception.domain.CollaborationRequestNotFoundException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.collaboration.dto.AcceptRequestResponse;
import com.planmate.domain.collaboration.dto.GetReceivedPendingRequestsResponse;
import com.planmate.domain.collaboration.dto.InviteUserToPlanResponse;
import com.planmate.domain.collaboration.dto.RejectRequestResponse;
import com.planmate.domain.collaboration.dto.RequestEditAccessResponse;
import com.planmate.domain.collaboration.entity.CollaborationRequest;
import com.planmate.domain.collaboration.enums.CollaborationRequestStatus;
import com.planmate.domain.collaboration.enums.CollaborationRequestType;
import com.planmate.domain.collaboration.repository.CollaborationRequestRepository;
import com.planmate.domain.notification.CollaborationNotificationEvent;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CollaborationRequestServiceTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID RECEIVER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Mock
    private CollaborationRequestRepository collaborationRequestRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private PlanMemberRepository planMemberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CollaborationRequestService collaborationRequestService;

    private User sender() {
        return User.builder().userId(SENDER_ID).nickname("sender").build();
    }

    private User receiver() {
        return User.builder().userId(RECEIVER_ID).nickname("receiver").build();
    }

    private User owner() {
        return User.builder().userId(OWNER_ID).nickname("owner").build();
    }

    private Plan planStub(User owner) {
        // planName은 이번 변경 전엔 아무도 안 읽었지만, 이벤트 발행 코드(Map.of(...))가 null이면
        // Map.of()가 NullPointerException을 던지므로 이제부터 채워야 함(기존 로직 변경 아님, 새로 생긴 요구사항).
        return Plan.builder().planId(PLAN_ID).planName("plan").user(owner).build();
    }

    private CollaborationRequest savedRequestStub() {
        return CollaborationRequest.builder().collaborationRequestId(1L).build();
    }

    private CollaborationRequest requestStub(Long id, CollaborationRequestType type, CollaborationRequestStatus status,
            User sender, User receiver, Plan plan) {
        return CollaborationRequest.builder()
                .collaborationRequestId(id)
                .type(type)
                .status(status)
                .sender(sender)
                .receiver(receiver)
                .plan(plan)
                .build();
    }

    // ---------- inviteUserToPlan (#37) ----------

    @Test
    @DisplayName("사용자 초대 성공 - INVITE/PENDING 요청 생성")
    void inviteUserToPlan_success() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender()));
        given(userRepository.findByNickname("receiver")).willReturn(Optional.of(receiver()));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, RECEIVER_ID)).willReturn(false);
        given(collaborationRequestRepository.existsPendingRequest(
                SENDER_ID, RECEIVER_ID, PLAN_ID, CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING))
                .willReturn(false);
        given(collaborationRequestRepository.save(any(CollaborationRequest.class))).willReturn(savedRequestStub());

        InviteUserToPlanResponse response =
                collaborationRequestService.inviteUserToPlan(PLAN_ID, SENDER_ID, "receiver");

        assertThat(response.collaborationRequestId()).isEqualTo(1L);

        ArgumentCaptor<CollaborationRequest> captor = ArgumentCaptor.forClass(CollaborationRequest.class);
        verify(collaborationRequestRepository).save(captor.capture());
        CollaborationRequest built = captor.getValue();
        assertThat(built.getType()).isEqualTo(CollaborationRequestType.INVITE);
        assertThat(built.getStatus()).isEqualTo(CollaborationRequestStatus.PENDING);
        assertThat(built.getSender().getUserId()).isEqualTo(SENDER_ID);
        assertThat(built.getReceiver().getUserId()).isEqualTo(RECEIVER_ID);

        verify(eventPublisher).publishEvent(new CollaborationNotificationEvent(
                RECEIVER_ID,
                "invitation",
                Map.of("type", "INVITE", "senderNickname", "sender", "planName", "plan", "collaborationRequestId", 1L),
                "일정 초대",
                "sender님이 '" + "plan" + "' 일정에 초대했습니다."
        ));
    }

    @Test
    @DisplayName("사용자 초대 실패 - 플랜 없음")
    void inviteUserToPlan_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.inviteUserToPlan(PLAN_ID, SENDER_ID, "receiver"))
                .isInstanceOf(PlanNotFoundException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("사용자 초대 실패 - 보낸 사람(본인) 조회 실패(방어적 케이스)")
    void inviteUserToPlan_fail_senderNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.inviteUserToPlan(PLAN_ID, SENDER_ID, "receiver"))
                .isInstanceOf(UserNotFoundException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("사용자 초대 실패 - 닉네임에 해당하는 사용자 없음")
    void inviteUserToPlan_fail_receiverNicknameNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender()));
        given(userRepository.findByNickname("nobody")).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.inviteUserToPlan(PLAN_ID, SENDER_ID, "nobody"))
                .isInstanceOf(UserNotFoundException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("사용자 초대 실패 - 받는 사람이 이미 이 플랜의 멤버(자기 자신 초대도 이 경로로 걸러짐)")
    void inviteUserToPlan_fail_receiverAlreadyMember() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender()));
        given(userRepository.findByNickname("receiver")).willReturn(Optional.of(receiver()));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, RECEIVER_ID)).willReturn(true);

        assertThatThrownBy(() -> collaborationRequestService.inviteUserToPlan(PLAN_ID, SENDER_ID, "receiver"))
                .isInstanceOf(CollaborationRequestAlreadyMemberException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("사용자 초대 실패 - 동일한 INVITE 요청이 이미 대기 중")
    void inviteUserToPlan_fail_duplicatePending() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender()));
        given(userRepository.findByNickname("receiver")).willReturn(Optional.of(receiver()));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, RECEIVER_ID)).willReturn(false);
        given(collaborationRequestRepository.existsPendingRequest(
                SENDER_ID, RECEIVER_ID, PLAN_ID, CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING))
                .willReturn(true);

        assertThatThrownBy(() -> collaborationRequestService.inviteUserToPlan(PLAN_ID, SENDER_ID, "receiver"))
                .isInstanceOf(CollaborationRequestDuplicatePendingException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    // ---------- requestEditAccess (#38) ----------

    @Test
    @DisplayName("편집 권한 요청 성공 - REQUEST/PENDING 요청 생성, receiver=플랜 오너")
    void requestEditAccess_success() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender()));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, SENDER_ID)).willReturn(false);
        given(collaborationRequestRepository.existsPendingRequest(
                SENDER_ID, OWNER_ID, PLAN_ID, CollaborationRequestType.REQUEST, CollaborationRequestStatus.PENDING))
                .willReturn(false);
        given(collaborationRequestRepository.save(any(CollaborationRequest.class))).willReturn(savedRequestStub());

        RequestEditAccessResponse response = collaborationRequestService.requestEditAccess(PLAN_ID, SENDER_ID);

        assertThat(response.collaborationRequestId()).isEqualTo(1L);

        ArgumentCaptor<CollaborationRequest> captor = ArgumentCaptor.forClass(CollaborationRequest.class);
        verify(collaborationRequestRepository).save(captor.capture());
        CollaborationRequest built = captor.getValue();
        assertThat(built.getType()).isEqualTo(CollaborationRequestType.REQUEST);
        assertThat(built.getStatus()).isEqualTo(CollaborationRequestStatus.PENDING);
        assertThat(built.getSender().getUserId()).isEqualTo(SENDER_ID);
        assertThat(built.getReceiver().getUserId()).isEqualTo(OWNER_ID);

        verify(eventPublisher).publishEvent(new CollaborationNotificationEvent(
                OWNER_ID,
                "invitation",
                Map.of("type", "REQUEST", "senderNickname", "sender", "planName", "plan", "collaborationRequestId", 1L),
                "편집 권한 요청",
                "sender님이 '" + "plan" + "' 일정의 편집 권한을 요청했습니다."
        ));
    }

    @Test
    @DisplayName("편집 권한 요청 실패 - 플랜 없음")
    void requestEditAccess_fail_planNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.requestEditAccess(PLAN_ID, SENDER_ID))
                .isInstanceOf(PlanNotFoundException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("편집 권한 요청 실패 - 보낸 사람(본인) 조회 실패(방어적 케이스)")
    void requestEditAccess_fail_senderNotFound() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.requestEditAccess(PLAN_ID, SENDER_ID))
                .isInstanceOf(UserNotFoundException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("편집 권한 요청 실패 - 보낸 사람이 이미 이 플랜의 멤버(오너 자기 자신 요청도 이 경로로 걸러짐)")
    void requestEditAccess_fail_senderAlreadyMember() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender()));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, SENDER_ID)).willReturn(true);

        assertThatThrownBy(() -> collaborationRequestService.requestEditAccess(PLAN_ID, SENDER_ID))
                .isInstanceOf(CollaborationRequestAlreadyMemberException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("편집 권한 요청 실패 - 동일한 REQUEST 요청이 이미 대기 중")
    void requestEditAccess_fail_duplicatePending() {
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(planStub(owner())));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender()));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, SENDER_ID)).willReturn(false);
        given(collaborationRequestRepository.existsPendingRequest(
                SENDER_ID, OWNER_ID, PLAN_ID, CollaborationRequestType.REQUEST, CollaborationRequestStatus.PENDING))
                .willReturn(true);

        assertThatThrownBy(() -> collaborationRequestService.requestEditAccess(PLAN_ID, SENDER_ID))
                .isInstanceOf(CollaborationRequestDuplicatePendingException.class);
        verify(collaborationRequestRepository, never()).save(any());
    }

    // ---------- getReceivedPendingRequests (#39) ----------

    @Test
    @DisplayName("받은 대기 요청 목록 조회 성공 - INVITE/REQUEST 섞여서 매핑됨")
    void getReceivedPendingRequests_success() {
        Plan plan = planStub(owner());
        CollaborationRequest invite = requestStub(1L, CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING,
                owner(), sender(), plan);
        given(collaborationRequestRepository.findByReceiverUserIdAndStatus(OWNER_ID, CollaborationRequestStatus.PENDING))
                .willReturn(List.of(invite));

        GetReceivedPendingRequestsResponse response = collaborationRequestService.getReceivedPendingRequests(OWNER_ID);

        assertThat(response.requests()).hasSize(1);
        assertThat(response.requests().get(0).collaborationRequestId()).isEqualTo(1L);
        assertThat(response.requests().get(0).senderId()).isEqualTo(OWNER_ID);
        assertThat(response.requests().get(0).type()).isEqualTo(CollaborationRequestType.INVITE);
    }

    @Test
    @DisplayName("받은 대기 요청 목록 조회 성공 - 대기 중인 요청 없으면 빈 리스트")
    void getReceivedPendingRequests_success_empty() {
        given(collaborationRequestRepository.findByReceiverUserIdAndStatus(OWNER_ID, CollaborationRequestStatus.PENDING))
                .willReturn(List.of());

        GetReceivedPendingRequestsResponse response = collaborationRequestService.getReceivedPendingRequests(OWNER_ID);

        assertThat(response.requests()).isEmpty();
    }

    // ---------- acceptRequest (#40) ----------

    @Test
    @DisplayName("요청 수락 성공 - INVITE 타입: 받는 사람(receiver)이 새 편집자, invited_by=sender")
    void acceptRequest_success_inviteType() {
        Plan plan = planStub(owner());
        CollaborationRequest request = requestStub(1L, CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING,
                owner(), receiver(), plan);
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.of(request));
        given(userRepository.findById(RECEIVER_ID)).willReturn(Optional.of(receiver()));
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, RECEIVER_ID)).willReturn(false);

        AcceptRequestResponse response = collaborationRequestService.acceptRequest(1L);

        assertThat(response.planId()).isEqualTo(PLAN_ID);
        assertThat(request.getStatus()).isEqualTo(CollaborationRequestStatus.ACCEPTED);

        ArgumentCaptor<PlanMember> captor = ArgumentCaptor.forClass(PlanMember.class);
        verify(planMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getUser().getUserId()).isEqualTo(RECEIVER_ID);
        assertThat(captor.getValue().getRole()).isEqualTo(PlanMemberRole.EDITOR);
        assertThat(captor.getValue().getInvitedBy().getUserId()).isEqualTo(OWNER_ID);

        // 알림 수신자는 request.getSender()(=owner, 응답을 기다리던 원래 요청자) — receiver가 아님
        verify(eventPublisher).publishEvent(new CollaborationNotificationEvent(
                OWNER_ID,
                "requestResult",
                Map.of("result", "ACCEPTED", "receiverNickname", "receiver", "planName", "plan"),
                "요청 수락",
                "receiver님이 '" + "plan" + "' 관련 요청을 수락했습니다."
        ));
    }

    @Test
    @DisplayName("요청 수락 성공 - REQUEST 타입: 보낸 사람(sender)이 새 편집자, invited_by=receiver(오너)")
    void acceptRequest_success_requestType() {
        Plan plan = planStub(owner());
        CollaborationRequest request = requestStub(1L, CollaborationRequestType.REQUEST, CollaborationRequestStatus.PENDING,
                sender(), owner(), plan);
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.of(request));
        given(userRepository.findById(SENDER_ID)).willReturn(Optional.of(sender()));
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, SENDER_ID)).willReturn(false);

        AcceptRequestResponse response = collaborationRequestService.acceptRequest(1L);

        assertThat(response.planId()).isEqualTo(PLAN_ID);

        ArgumentCaptor<PlanMember> captor = ArgumentCaptor.forClass(PlanMember.class);
        verify(planMemberRepository).save(captor.capture());
        assertThat(captor.getValue().getUser().getUserId()).isEqualTo(SENDER_ID);
        assertThat(captor.getValue().getInvitedBy().getUserId()).isEqualTo(OWNER_ID);

        // REQUEST 타입은 sender/receiver가 INVITE와 반대 방향(requestStub(..., sender(), owner(), ...)) — 알림은 request.getSender()=SENDER_ID
        verify(eventPublisher).publishEvent(new CollaborationNotificationEvent(
                SENDER_ID,
                "requestResult",
                Map.of("result", "ACCEPTED", "receiverNickname", "owner", "planName", "plan"),
                "요청 수락",
                "owner님이 '" + "plan" + "' 관련 요청을 수락했습니다."
        ));
    }

    @Test
    @DisplayName("요청 수락 성공 - 이미 편집자인 경우 PlanMember 재삽입 없이 상태만 ACCEPTED로 변경(원본 idempotent 동작 유지)")
    void acceptRequest_success_alreadyMemberSkipsInsert() {
        Plan plan = planStub(owner());
        CollaborationRequest request = requestStub(1L, CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING,
                owner(), receiver(), plan);
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.of(request));
        given(userRepository.findById(RECEIVER_ID)).willReturn(Optional.of(receiver()));
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(plan));
        given(planMemberRepository.existsByPlanPlanIdAndUserUserId(PLAN_ID, RECEIVER_ID)).willReturn(true);

        collaborationRequestService.acceptRequest(1L);

        assertThat(request.getStatus()).isEqualTo(CollaborationRequestStatus.ACCEPTED);
        verify(planMemberRepository, never()).save(any());

        // PlanMember 재삽입은 스킵돼도 알림은 무조건 발행(원본 동일 동작, 11절 근거)
        verify(eventPublisher).publishEvent(any(CollaborationNotificationEvent.class));
    }

    @Test
    @DisplayName("요청 수락 실패 - 요청 없음")
    void acceptRequest_fail_requestNotFound() {
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.acceptRequest(1L))
                .isInstanceOf(CollaborationRequestNotFoundException.class);
    }

    @Test
    @DisplayName("요청 수락 실패 - 이미 처리된 요청(ACCEPTED)")
    void acceptRequest_fail_alreadyProcessed() {
        Plan plan = planStub(owner());
        CollaborationRequest request = requestStub(1L, CollaborationRequestType.INVITE, CollaborationRequestStatus.ACCEPTED,
                owner(), receiver(), plan);
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.of(request));

        assertThatThrownBy(() -> collaborationRequestService.acceptRequest(1L))
                .isInstanceOf(CollaborationRequestAlreadyProcessedException.class);
        verify(planMemberRepository, never()).save(any());
    }

    @Test
    @DisplayName("요청 수락 실패 - 연결된 플랜이 그사이 소프트 삭제됨(방어적 재확인)")
    void acceptRequest_fail_planNotFound() {
        Plan plan = planStub(owner());
        CollaborationRequest request = requestStub(1L, CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING,
                owner(), receiver(), plan);
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.of(request));
        given(userRepository.findById(RECEIVER_ID)).willReturn(Optional.of(receiver()));
        given(planRepository.findById(PLAN_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.acceptRequest(1L))
                .isInstanceOf(PlanNotFoundException.class);
        verify(planMemberRepository, never()).save(any());
    }

    @Test
    @DisplayName("요청 수락 실패 - 새 편집자가 될 대상이 그사이 탈퇴(소프트 삭제)됨(17장 도메인 전체 점검에서 발견 — 유령 plan_member 생성 방지)")
    void acceptRequest_fail_targetUserWithdrawn() {
        Plan plan = planStub(owner());
        CollaborationRequest request = requestStub(1L, CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING,
                owner(), receiver(), plan);
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.of(request));
        given(userRepository.findById(RECEIVER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.acceptRequest(1L))
                .isInstanceOf(UserNotFoundException.class);
        verify(planMemberRepository, never()).save(any());
    }

    // ---------- rejectRequest (#41) ----------

    @Test
    @DisplayName("요청 거절 성공 - 상태만 REJECTED로 변경, 멤버십 변경 없음")
    void rejectRequest_success() {
        Plan plan = planStub(owner());
        CollaborationRequest request = requestStub(1L, CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING,
                owner(), receiver(), plan);
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.of(request));

        RejectRequestResponse response = collaborationRequestService.rejectRequest(1L);

        assertThat(response.collaborationRequestId()).isEqualTo(1L);
        assertThat(request.getStatus()).isEqualTo(CollaborationRequestStatus.REJECTED);
        verify(planMemberRepository, never()).save(any());

        verify(eventPublisher).publishEvent(new CollaborationNotificationEvent(
                OWNER_ID,
                "requestResult",
                Map.of("result", "REJECTED", "receiverNickname", "receiver", "planName", "plan"),
                "요청 거절",
                "receiver님이 '" + "plan" + "' 관련 요청을 거절했습니다."
        ));
    }

    @Test
    @DisplayName("요청 거절 실패 - 요청 없음")
    void rejectRequest_fail_requestNotFound() {
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collaborationRequestService.rejectRequest(1L))
                .isInstanceOf(CollaborationRequestNotFoundException.class);
    }

    @Test
    @DisplayName("요청 거절 실패 - 이미 처리된 요청(REJECTED)")
    void rejectRequest_fail_alreadyProcessed() {
        Plan plan = planStub(owner());
        CollaborationRequest request = requestStub(1L, CollaborationRequestType.INVITE, CollaborationRequestStatus.REJECTED,
                owner(), receiver(), plan);
        given(collaborationRequestRepository.findById(1L)).willReturn(Optional.of(request));

        assertThatThrownBy(() -> collaborationRequestService.rejectRequest(1L))
                .isInstanceOf(CollaborationRequestAlreadyProcessedException.class);
    }
}
