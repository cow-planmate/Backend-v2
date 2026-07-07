package com.planmate.domain.collaboration.service;

import com.planmate.common.exception.domain.CollaborationRequestAlreadyMemberException;
import com.planmate.common.exception.domain.CollaborationRequestDuplicatePendingException;
import com.planmate.common.exception.domain.PlanNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.collaboration.dto.InviteUserToPlanResponse;
import com.planmate.domain.collaboration.dto.RequestEditAccessResponse;
import com.planmate.domain.collaboration.entity.CollaborationRequest;
import com.planmate.domain.collaboration.enums.CollaborationRequestStatus;
import com.planmate.domain.collaboration.enums.CollaborationRequestType;
import com.planmate.domain.collaboration.repository.CollaborationRequestRepository;
import com.planmate.domain.plan.entity.Plan;
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
        return Plan.builder().planId(PLAN_ID).user(owner).build();
    }

    private CollaborationRequest savedRequestStub() {
        return CollaborationRequest.builder().collaborationRequestId(1L).build();
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
}
