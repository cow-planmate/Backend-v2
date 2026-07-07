package com.planmate.domain.plan.dto;

import java.util.UUID;

public record EditorDto(
        UUID userId,
        String nickname
) {
}
