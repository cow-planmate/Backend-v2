package com.planmate.domain.collaboration.dto;

import java.util.List;

public record GetReceivedPendingRequestsResponse(
        List<PendingRequestDto> requests
) {
}
