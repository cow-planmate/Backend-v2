package com.planmate.domain.chatbot.dto;

/**
 * 목적지 → 한국관광공사 법정동 지역코드 쌍. {@code ldongSignguCd} 는 광역시(시군구 없음)면 null.
 * planmate-ai {@code userContext.regionCodes[]} 미러.
 */
public record RegionCodeDto(
        String ldongRegnCd,
        String ldongSignguCd
) {
}
