package com.planmate.domain.plan.dto;

import com.planmate.domain.timetable.enums.BlockCategory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record TimetablePlaceBlockDto(
        @NotNull LocalDate date,
        @NotNull BlockCategory blockCategory,
        String placeId,
        @NotBlank String placeName,
        String placeContentTypeId,
        String placeAddress,
        String placeThumbnailUrl,
        String placeCopyrightDivCd,
        BigDecimal latitude,
        BigDecimal longitude,
        @NotNull LocalTime blockStartTime,
        @NotNull LocalTime blockEndTime,
        String memo
) {
    @AssertTrue(message = "블록 시작 시간은 종료 시간보다 늦을 수 없습니다.")
    public boolean isValidTimeRange() {
        if (blockStartTime == null || blockEndTime == null) {
            return true;
        }
        return !blockStartTime.isAfter(blockEndTime);
    }
}
