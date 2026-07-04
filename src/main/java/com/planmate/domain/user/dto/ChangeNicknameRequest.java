package com.planmate.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeNicknameRequest(
        @NotBlank @Size(min = 2, max = 20) String nickname
) {
}
