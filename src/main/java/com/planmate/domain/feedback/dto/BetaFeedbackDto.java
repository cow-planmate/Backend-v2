package com.planmate.domain.feedback.dto;

import com.planmate.domain.feedback.entity.BetaFeedback;

import java.time.LocalDateTime;

public record BetaFeedbackDto(
        Long feedbackId,
        String content,
        LocalDateTime createdAt
) {
    public static BetaFeedbackDto from(BetaFeedback betaFeedback) {
        return new BetaFeedbackDto(
                betaFeedback.getFeedbackId(),
                betaFeedback.getContent(),
                betaFeedback.getCreatedAt()
        );
    }
}
