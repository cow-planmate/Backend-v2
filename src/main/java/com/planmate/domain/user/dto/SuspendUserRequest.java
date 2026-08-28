package com.planmate.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record SuspendUserRequest(
        LocalDateTime until,   // null = 영구 정지
        @NotBlank(message = "정지 사유를 입력해주세요.")
        @Size(max = 255, message = "정지 사유는 255자를 넘을 수 없습니다.")
        String reason
) {
}
