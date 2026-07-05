package com.planmate.domain.oauth.dto;

import com.planmate.domain.user.enums.Gender;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record OAuthCompleteRequest(
        @NotBlank String signupId,
        String email,            // 검증 없음 — 조건부 필수(비즈니스 검증, Service에서 처리)
        LocalDate birthdate,     // 검증 없음 — 선택 입력, User 엔티티가 nullable
        Gender gender             // 검증 없음 — 선택 입력, User 엔티티가 nullable
) {
}
