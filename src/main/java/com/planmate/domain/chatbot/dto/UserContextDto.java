package com.planmate.domain.chatbot.dto;

import java.util.List;

/**
 * v2 가 앱 DB 에서 조립해 planmate-ai 로 보내는 사용자 컨텍스트.
 * planmate-ai {@code ChatbotRequest.userContext} 미러 (planmate-ai/project_ai/13 §2-2).
 *
 * <p>{@code adultCount}/{@code childCount} 는 {@code planContext.planFrame} 에 있으므로 중복해 두지 않는다.
 * 설계: project_ai/04_기능/ai/06_챗봇_연동_설계.md §4-2.
 */
public record UserContextDto(
        List<PreferredThemeDto> preferredThemes,
        List<RegionCodeDto> regionCodes,
        List<String> signguCodes
) {
}
