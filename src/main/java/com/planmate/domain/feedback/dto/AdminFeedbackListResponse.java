package com.planmate.domain.feedback.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record AdminFeedbackListResponse(
        List<BetaFeedbackDto> feedbacks
) {
}
