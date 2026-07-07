package com.planmate.domain.collaboration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteUserToPlanRequest(
        @NotBlank @Size(min = 2, max = 20) String receiverNickname
) {
}
