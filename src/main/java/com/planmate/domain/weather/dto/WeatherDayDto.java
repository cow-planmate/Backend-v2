package com.planmate.domain.weather.dto;

import java.time.LocalDate;

public record WeatherDayDto(
        LocalDate date,
        String description,
        double tempMin,
        double tempMax,
        double feelsLike,
        WeatherDataSource dataSource
) {
}
