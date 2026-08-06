package com.planmate.domain.checklist.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderChecklistItemsRequest(
        @NotEmpty(message = "정렬할 항목 id 목록은 비어 있을 수 없습니다.")
        List<Long> itemIds
) {
}
