package com.planmate.domain.collaboration.dto;

import com.planmate.domain.collaboration.enums.CollaborationRequestStatus;
import com.planmate.domain.collaboration.enums.CollaborationRequestType;
import java.util.UUID;

public record CollaborationRequestStatusDto(Long collaborationRequestId, CollaborationRequestStatus status,
                                            CollaborationRequestType type, UUID planId) {}
