package com.planmate.domain.plan.dto;

import java.util.List;

public record GetEditorsResponse(
        List<EditorDto> editors
) {
}
