package com.planmate.domain.feedback.dto;

import jakarta.validation.constraints.NotBlank;

public record BetaFeedbackRequest(
        @NotBlank(message = "피드백 내용을 입력해주세요.")
        String content
) {
}
