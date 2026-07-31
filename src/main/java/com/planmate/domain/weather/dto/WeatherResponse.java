package com.planmate.domain.weather.dto;

import java.util.List;

public record WeatherResponse(
        List<WeatherDayDto> weather,
        String recommendation
) {
}
