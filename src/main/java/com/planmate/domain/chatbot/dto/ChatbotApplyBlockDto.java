package com.planmate.domain.chatbot.dto;

import com.planmate.domain.timetable.enums.BlockCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * [반영] working copy 안의 블록 1개. {@code GetCompletePlanResponse.placeBlocks[]}(=
 * {@link com.planmate.domain.plan.dto.TimetablePlaceBlockDetailDto})와 달리 두 가지가 다르다:
 * <ul>
 *   <li>{@code date} 를 직접 갖는다 — 새 블록은 {@code timeTableId} 가 아직 없어 날짜로만 자신이
 *       속할 타임테이블을 가리킬 수 있다(설계 §2).</li>
 *   <li>{@code blockId}/{@code timeTableId} 가 nullable 이다 — 없으면 새 블록.</li>
 * </ul>
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §2.
 */
public record ChatbotApplyBlockDto(

        // null = 새 블록. 있으면 저장본의 그 blockId 를 가리킴(§5-1 — 자연키 매칭 안 함).
        Long blockId,

        // null 가능 — 아직 id 없는 새 타임테이블(오늘 막 추가된 날)에 속한 새 블록일 때.
        Long timeTableId,

        @NotNull
        LocalDate date,

        String placeId,

        // 엔티티 nullable=false(TimeTablePlaceBlock.placeName) — 방어적으로 여기서도 검증.
        @NotBlank
        String placeName,

        String placeContentTypeId,
        String placeAddress,
        String placeThumbnailUrl,
        String placeCopyrightDivCd,
        BigDecimal latitude,
        BigDecimal longitude,

        @NotNull
        LocalTime blockStartTime,

        @NotNull
        LocalTime blockEndTime,

        @NotNull
        BlockCategory blockCategory,

        String memo
) {
}
