package com.planmate.domain.chatbot.dto;

/**
 * 사용자 선호 테마를 TourAPI lclsSystm 분류로 평탄화한 것. {@code level} = 1/2/3 (매칭할 lclsSystm 컬럼 깊이).
 * planmate-ai {@code userContext.preferredThemes[]} 미러.
 */
public record PreferredThemeDto(
        Integer level,
        String code
) {
}
