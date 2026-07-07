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
import com.planmate.domain.collaboration.dto.PendingRequestDto;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CollaborationRequestService {

    private final CollaborationRequestRepository collaborationRequestRepository;
    private final PlanRepository planRepository;
    private final PlanMemberRepository planMemberRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    @Transactional
    public InviteUserToPlanResponse inviteUserToPlan(UUID planId, UUID senderId, String receiverNickname) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);
        User sender = userRepository.findById(senderId)
                .orElseThrow(UserNotFoundException::new);
        User receiver = userRepository.findByNickname(receiverNickname)
                .orElseThrow(UserNotFoundException::new);

        if (planMemberRepository.existsByPlanPlanIdAndUserUserId(planId, receiver.getUserId())) {
            throw new CollaborationRequestAlreadyMemberException();
        }
        if (collaborationRequestRepository.existsPendingRequest(
                senderId, receiver.getUserId(), planId,
                CollaborationRequestType.INVITE, CollaborationRequestStatus.PENDING)) {
            throw new CollaborationRequestDuplicatePendingException();
        }

        CollaborationRequest saved = collaborationRequestRepository.save(CollaborationRequest.builder()
                .type(CollaborationRequestType.INVITE)
                .status(CollaborationRequestStatus.PENDING)
                .sender(sender)
                .receiver(receiver)
                .plan(plan)
                .build());

        eventPublisher.publishEvent(new CollaborationNotificationEvent(
                receiver.getUserId(),
                "invitation",
                Map.of(
                        "type", "INVITE",
                        "senderNickname", sender.getNickname(),
                        "planName", plan.getPlanName(),
                        "collaborationRequestId", saved.getCollaborationRequestId()
                ),
                "일정 초대",
                sender.getNickname() + "님이 '" + plan.getPlanName() + "' 일정에 초대했습니다."
        ));

        return new InviteUserToPlanResponse(saved.getCollaborationRequestId());
    }

    @Transactional
    public RequestEditAccessResponse requestEditAccess(UUID planId, UUID senderId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(PlanNotFoundException::new);
        User sender = userRepository.findById(senderId)
                .orElseThrow(UserNotFoundException::new);
        User owner = plan.getUser();

        if (planMemberRepository.existsByPlanPlanIdAndUserUserId(planId, senderId)) {
            throw new CollaborationRequestAlreadyMemberException();
        }
        if (collaborationRequestRepository.existsPendingRequest(
                senderId, owner.getUserId(), planId,
                CollaborationRequestType.REQUEST, CollaborationRequestStatus.PENDING)) {
            throw new CollaborationRequestDuplicatePendingException();
        }

        CollaborationRequest saved = collaborationRequestRepository.save(CollaborationRequest.builder()
                .type(CollaborationRequestType.REQUEST)
                .status(CollaborationRequestStatus.PENDING)
                .sender(sender)
                .receiver(owner)
                .plan(plan)
                .build());

        eventPublisher.publishEvent(new CollaborationNotificationEvent(
                owner.getUserId(),
                "invitation",
                Map.of(
                        "type", "REQUEST",
                        "senderNickname", sender.getNickname(),
                        "planName", plan.getPlanName(),
                        "collaborationRequestId", saved.getCollaborationRequestId()
                ),
                "편집 권한 요청",
                sender.getNickname() + "님이 '" + plan.getPlanName() + "' 일정의 편집 권한을 요청했습니다."
        ));

        return new RequestEditAccessResponse(saved.getCollaborationRequestId());
    }

    @Transactional(readOnly = true)
    public GetReceivedPendingRequestsResponse getReceivedPendingRequests(UUID userId) {
        List<PendingRequestDto> requests = collaborationRequestRepository
                .findByReceiverUserIdAndStatus(userId, CollaborationRequestStatus.PENDING).stream()
                .map(request -> new PendingRequestDto(
                        request.getCollaborationRequestId(),
                        request.getSender().getUserId(),
                        request.getSender().getNickname(),
                        request.getPlan().getPlanId(),
                        request.getPlan().getPlanName(),
                        request.getType()))
                .toList();
        return new GetReceivedPendingRequestsResponse(requests);
    }

    @PreAuthorize("@collaborationRequestAccessValidator.validateReceiver(#collaborationRequestId, authentication.principal)")
    @Transactional
    public AcceptRequestResponse acceptRequest(Long collaborationRequestId) {
        CollaborationRequest request = collaborationRequestRepository.findById(collaborationRequestId)
                .orElseThrow(CollaborationRequestNotFoundException::new);
        if (request.getStatus() != CollaborationRequestStatus.PENDING) {
            throw new CollaborationRequestAlreadyProcessedException();
        }

        User newMember = userRepository.findById(request.getTargetUserForAcceptance().getUserId())
                .orElseThrow(UserNotFoundException::new);
        Plan plan = planRepository.findById(request.getPlan().getPlanId())
                .orElseThrow(PlanNotFoundException::new);

        if (!planMemberRepository.existsByPlanPlanIdAndUserUserId(plan.getPlanId(), newMember.getUserId())) {
            planMemberRepository.save(PlanMember.builder()
                    .plan(plan)
                    .user(newMember)
                    .role(PlanMemberRole.EDITOR)
                    .invitedBy(request.getApprover())
                    .build());
        }

        request.accept();

        eventPublisher.publishEvent(new CollaborationNotificationEvent(
                request.getSender().getUserId(),
                "requestResult",
                Map.of(
                        "result", "ACCEPTED",
                        "receiverNickname", request.getReceiver().getNickname(),
                        "planName", plan.getPlanName()
                ),
                "요청 수락",
                request.getReceiver().getNickname() + "님이 '" + plan.getPlanName() + "' 관련 요청을 수락했습니다."
        ));

        return new AcceptRequestResponse(plan.getPlanId());
    }

    @PreAuthorize("@collaborationRequestAccessValidator.validateReceiver(#collaborationRequestId, authentication.principal)")
    @Transactional
    public RejectRequestResponse rejectRequest(Long collaborationRequestId) {
        CollaborationRequest request = collaborationRequestRepository.findById(collaborationRequestId)
                .orElseThrow(CollaborationRequestNotFoundException::new);
        if (request.getStatus() != CollaborationRequestStatus.PENDING) {
            throw new CollaborationRequestAlreadyProcessedException();
        }

        request.reject();

        eventPublisher.publishEvent(new CollaborationNotificationEvent(
                request.getSender().getUserId(),
                "requestResult",
                Map.of(
                        "result", "REJECTED",
                        "receiverNickname", request.getReceiver().getNickname(),
                        "planName", request.getPlan().getPlanName()
                ),
                "요청 거절",
                request.getReceiver().getNickname() + "님이 '" + request.getPlan().getPlanName() + "' 관련 요청을 거절했습니다."
        ));

        return new RejectRequestResponse(request.getCollaborationRequestId());
    }
}
