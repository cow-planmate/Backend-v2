package com.planmate.domain.collaboration.controller;

import com.planmate.common.exception.PlanMateException;
import com.planmate.common.exception.ErrorCode;
import com.planmate.domain.collaboration.dto.CollaborationRequestStatusDto;
import com.planmate.domain.collaboration.dto.AcceptRequestResponse;
import com.planmate.domain.collaboration.dto.GetReceivedPendingRequestsResponse;
import com.planmate.domain.collaboration.dto.RejectRequestResponse;
import com.planmate.domain.collaboration.service.CollaborationRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/collaboration-requests")
@RequiredArgsConstructor
public class CollaborationRequestController {

    private final CollaborationRequestService collaborationRequestService;

    @GetMapping("/statuses")
    public ResponseEntity<List<CollaborationRequestStatusDto>> getStatuses(
            @AuthenticationPrincipal UUID userId, @RequestParam(required = false) List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new PlanMateException(ErrorCode.INVALID_INPUT, "요청 ID는 양수로 1~100개까지 조회할 수 있습니다.");
        }
        return ResponseEntity.ok(collaborationRequestService.getStatuses(userId, ids.stream().distinct().toList()));
    }

    @GetMapping("/pending")
    public ResponseEntity<GetReceivedPendingRequestsResponse> getReceivedPendingRequests(
            @AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(collaborationRequestService.getReceivedPendingRequests(userId));
    }

    @PostMapping("/{collaborationRequestId}/accept")
    public ResponseEntity<AcceptRequestResponse> acceptRequest(@PathVariable Long collaborationRequestId) {
        return ResponseEntity.ok(collaborationRequestService.acceptRequest(collaborationRequestId));
    }

    @PostMapping("/{collaborationRequestId}/reject")
    public ResponseEntity<RejectRequestResponse> rejectRequest(@PathVariable Long collaborationRequestId) {
        return ResponseEntity.ok(collaborationRequestService.rejectRequest(collaborationRequestId));
    }
}
