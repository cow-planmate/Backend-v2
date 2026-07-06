package com.planmate.domain.plan.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record TimetableDto(
        @NotNull LocalDate date,
        @NotNull LocalTime timeTableStartTime,
        @NotNull LocalTime timeTableEndTime
) {
    @AssertTrue(message = "시작 시간은 종료 시간보다 늦을 수 없습니다.")
    public boolean isValidTimeRange() {
        if (timeTableStartTime == null || timeTableEndTime == null) {
            return true;
        }
        return !timeTableStartTime.isAfter(timeTableEndTime);
    }
}
