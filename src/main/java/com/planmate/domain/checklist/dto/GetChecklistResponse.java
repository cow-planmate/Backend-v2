package com.planmate.domain.checklist.dto;

import java.util.List;

public record GetChecklistResponse(
        List<ChecklistItemDto> items
) {
}
