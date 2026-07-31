package com.planmate.domain.weather.dto;

public enum WeatherDataSource {
    FORECAST,          // 실시간 예보(오늘+15일 이내)
    LAST_YEAR_ACTUAL,  // 예보 범위를 벗어나 작년 동기간 실측치로 대체
    SEASONAL_AVERAGE,  // Open-Meteo 호출 자체가 실패해 계절 평균으로 대체(추정치)
}
