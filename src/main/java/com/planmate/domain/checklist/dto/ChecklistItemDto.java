package com.planmate.domain.checklist.dto;

import com.planmate.domain.checklist.entity.PlanChecklistItem;
import com.planmate.domain.checklist.entity.PlanPersonalChecklistItem;

public record ChecklistItemDto(
        Long itemId,
        String content,
        boolean isChecked,
        int sortOrder
) {
    public static ChecklistItemDto from(PlanChecklistItem item) {
        return new ChecklistItemDto(
                item.getChecklistItemId(),
                item.getContent(),
                item.isChecked(),
                item.getSortOrder()
        );
    }

    public static ChecklistItemDto from(PlanPersonalChecklistItem item) {
        return new ChecklistItemDto(
                item.getPersonalChecklistItemId(),
                item.getContent(),
                item.isChecked(),
                item.getSortOrder()
        );
    }
}
