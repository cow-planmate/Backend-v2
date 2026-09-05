package com.planmate.domain.chatbot.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * [반영] working copy 안의 타임테이블 1개. {@code timeTableId} 가 없으면 새 날.
 * 설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §2.
 */
public record ChatbotApplyTimetableDto(

        // null = 새 타임테이블(새 날). 있으면 저장본의 그 timeTableId 를 가리킴.
        Long timeTableId,

        @NotNull
        LocalDate date,

        @NotNull
        LocalTime timeTableStartTime,

        @NotNull
        LocalTime timeTableEndTime
) {
}
