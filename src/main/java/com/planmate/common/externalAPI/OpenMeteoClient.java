package com.planmate.common.externalAPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.weather.dto.WeatherDataSource;
import com.planmate.domain.weather.dto.WeatherDayDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Open-Meteo(무료 공개 API, 키 불필요) 호출 클라이언트.
 * 오늘+15일 이내는 forecast API, 그 이후는 작년 동기간 archive API로 대체 조회한다.
 * project_ai/04_기능/날씨/A_추천_조회.md 3장·7장.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OpenMeteoClient {

    private static final int FORECAST_LIMIT_DAYS = 15;
    private static final String DAILY_FIELDS = "weathercode,temperature_2m_max,temperature_2m_min,apparent_temperature_max";

    @Qualifier("openMeteoRestTemplate")
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public List<WeatherDayDto> getDailyWeather(BigDecimal latitude, BigDecimal longitude,
                                                LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now();
        LocalDate forecastLimit = today.plusDays(FORECAST_LIMIT_DAYS);
        boolean useArchive = endDate.isAfter(forecastLimit);

        String url = useArchive
                ? buildArchiveUrl(latitude, longitude, startDate.minusYears(1), endDate.minusYears(1))
                : buildForecastUrl(latitude, longitude, startDate, endDate);

        String body = callHttp(url);
        return parseDaily(body, useArchive);
    }

    private String buildForecastUrl(BigDecimal lat, BigDecimal lng, LocalDate start, LocalDate end) {
        return UriComponentsBuilder
                .fromUriString("https://api.open-meteo.com/v1/forecast")
                .queryParam("latitude", lat.toPlainString())
                .queryParam("longitude", lng.toPlainString())
                .queryParam("daily", DAILY_FIELDS)
                .queryParam("timezone", "auto")
                .queryParam("start_date", start)
                .queryParam("end_date", end)
                .build()
                .toUriString();
    }

    private String buildArchiveUrl(BigDecimal lat, BigDecimal lng, LocalDate start, LocalDate end) {
        return UriComponentsBuilder
                .fromUriString("https://archive-api.open-meteo.com/v1/archive")
                .queryParam("latitude", lat.toPlainString())
                .queryParam("longitude", lng.toPlainString())
                .queryParam("daily", DAILY_FIELDS)
                .queryParam("timezone", "auto")
                .queryParam("start_date", start)
                .queryParam("end_date", end)
                .build()
                .toUriString();
    }

    private String callHttp(String url) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new OpenMeteoCallFailedException("Open-Meteo API 응답 실패: " + response.getStatusCode());
            }
            return response.getBody();
        } catch (OpenMeteoCallFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenMeteoCallFailedException(e);
        }
    }

    private List<WeatherDayDto> parseDaily(String body, boolean useArchive) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode daily = root.path("daily");
            JsonNode timeArr = daily.path("time");
            JsonNode weatherCodeArr = daily.path("weathercode");
            JsonNode tempMaxArr = daily.path("temperature_2m_max");
            JsonNode tempMinArr = daily.path("temperature_2m_min");
            JsonNode feelsLikeArr = daily.path("apparent_temperature_max");

            WeatherDataSource dataSource = useArchive ? WeatherDataSource.LAST_YEAR_ACTUAL : WeatherDataSource.FORECAST;

            List<WeatherDayDto> days = new ArrayList<>();
            for (int i = 0; i < timeArr.size(); i++) {
                // archive 조회 시 작년 날짜로 결과가 오므로, 올해 날짜로 다시 매핑한다.
                LocalDate resultDate = LocalDate.parse(timeArr.get(i).asText());
                LocalDate displayDate = useArchive ? resultDate.plusYears(1) : resultDate;

                int code = weatherCodeArr.get(i).asInt();
                double maxTemp = tempMaxArr.get(i).asDouble();
                double minTemp = tempMinArr.get(i).asDouble();
                double feelsLike = feelsLikeArr.get(i).asDouble();

                days.add(new WeatherDayDto(
                        displayDate,
                        describeWeatherCode(code),
                        Math.round(minTemp),
                        Math.round(maxTemp),
                        Math.round(feelsLike),
                        dataSource
                ));
            }
            if (days.isEmpty()) {
                // JsonNode.path()는 필드가 없어도 예외 없이 빈 노드를 반환하므로,
                // daily 필드 자체가 없는 응답도 여기까지 조용히 통과할 수 있다 - 명시적으로 실패 처리한다.
                throw new OpenMeteoCallFailedException("Open-Meteo 응답에 daily 데이터가 없습니다.");
            }
            return days;
        } catch (OpenMeteoCallFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenMeteoCallFailedException(e);
        }
    }

    private String describeWeatherCode(int code) {
        return switch (code) {
            case 0 -> "맑음";
            case 1 -> "대체로 맑음";
            case 2 -> "구름 조금";
            case 3 -> "흐림";
            case 45, 48 -> "안개";
            case 51, 53, 55 -> "이슬비";
            case 61, 63, 65 -> "비";
            case 71, 73, 75 -> "눈";
            case 80, 81, 82 -> "소나기";
            case 95, 96, 99 -> "뇌우";
            default -> "정보 없음";
        };
    }
}
