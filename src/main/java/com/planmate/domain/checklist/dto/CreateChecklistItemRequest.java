package com.planmate.domain.checklist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChecklistItemRequest(
        @NotBlank(message = "체크리스트 항목 내용은 비어 있을 수 없습니다.")
        @Size(max = 255, message = "체크리스트 항목 내용은 255자를 넘을 수 없습니다.")
        String content
) {
}
