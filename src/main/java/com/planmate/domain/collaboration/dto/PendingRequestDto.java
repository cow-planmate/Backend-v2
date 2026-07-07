package com.planmate.domain.collaboration.dto;

import com.planmate.domain.collaboration.enums.CollaborationRequestType;

import java.util.UUID;

public record PendingRequestDto(
        Long collaborationRequestId,
        UUID senderId,
        String senderNickname,
        UUID planId,
        String planName,
        CollaborationRequestType type
) {
}
