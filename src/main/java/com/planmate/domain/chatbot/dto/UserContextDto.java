package com.planmate.domain.chatbot.dto;

import java.util.List;

/**
 * v2 가 앱 DB 에서 조립해 planmate-ai 로 보내는 사용자 컨텍스트.
 * planmate-ai {@code ChatbotRequest.userContext} 미러 (planmate-ai/project_ai/13 §2-2).
 *
 * <p>{@code adultCount}/{@code childCount} 는 {@code planContext.planFrame} 에 있으므로 중복해 두지 않는다.
 * <p>{@code regionIndexVersion} 은 v2 지역 색인의 버전이다. planmate-ai 는 자기가 들고 있는 색인과
 * 다르면 내부 gRPC 로 색인을 다시 받는다(planmate-ai/project_ai/22_지역_참조데이터_동기화_설계.md §5-4).
 * 설계: project_ai/04_기능/ai/06_챗봇_연동_설계.md §4-2.
 */
public record UserContextDto(
        List<PreferredThemeDto> preferredThemes,
        List<RegionCodeDto> regionCodes,
        List<String> signguCodes,
        String regionIndexVersion
) {
}
