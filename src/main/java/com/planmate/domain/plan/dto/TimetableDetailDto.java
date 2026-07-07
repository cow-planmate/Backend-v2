package com.planmate.domain.plan.dto;

import com.planmate.domain.timetable.entity.TimeTable;

import java.time.LocalDate;
import java.time.LocalTime;

public record TimetableDetailDto(
        Long timeTableId,
        LocalDate date,
        LocalTime timeTableStartTime,
        LocalTime timeTableEndTime
) {
    public static TimetableDetailDto from(TimeTable timeTable) {
        return new TimetableDetailDto(
                timeTable.getTimeTableId(),
                timeTable.getDate(),
                timeTable.getTimeTableStartTime(),
                timeTable.getTimeTableEndTime()
        );
    }
}
