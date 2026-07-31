package com.planmate.domain.weather.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

// GET 쿼리 파라미터 바인딩(@ModelAttribute). PlaceSearchRequest와 동일 패턴.
public record WeatherRequest(
        @NotNull Integer destinationId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate
) {
    @AssertTrue(message = "종료 날짜는 시작 날짜보다 빠를 수 없습니다.")
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) {
            return true; // 개별 @NotNull이 처리
        }
        return !endDate.isBefore(startDate);
    }
}
