package com.planmate.domain.collaboration.validator;

import com.planmate.common.exception.domain.CollaborationRequestAccessDeniedException;
import com.planmate.common.exception.domain.CollaborationRequestNotFoundException;
import com.planmate.domain.collaboration.entity.CollaborationRequest;
import com.planmate.domain.collaboration.repository.CollaborationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CollaborationRequestAccessValidator {

    private final CollaborationRequestRepository collaborationRequestRepository;

    // 존재 확인을 수신자 확인보다 먼저 한다 — 순서를 바꾸면 존재하지 않는 collaborationRequestId도
    // "권한 없음"(403)으로 뭉개져 404가 도달 불가능해진다(PlanAccessValidator와 동일 근거).
    public boolean validateReceiver(Long collaborationRequestId, UUID userId) {
        CollaborationRequest request = collaborationRequestRepository.findById(collaborationRequestId)
                .orElseThrow(CollaborationRequestNotFoundException::new);
        if (!request.getReceiver().getUserId().equals(userId)) {
            throw new CollaborationRequestAccessDeniedException();
        }
        return true;
    }
}
