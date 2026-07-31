package com.planmate.domain.weather.service;

import com.planmate.common.exception.domain.DestinationNotFoundException;
import com.planmate.common.externalAPI.OpenMeteoCallFailedException;
import com.planmate.common.externalAPI.OpenMeteoClient;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.weather.dto.WeatherDataSource;
import com.planmate.domain.weather.dto.WeatherDayDto;
import com.planmate.domain.weather.dto.WeatherRequest;
import com.planmate.domain.weather.dto.WeatherResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 목적지·기간 기준 날씨 추천 조회.
 * project_ai/04_기능/날씨/A_추천_조회.md 3장.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherService {

    private final DestinationRepository destinationRepository;
    private final OpenMeteoClient openMeteoClient;

    // 이 메서드 자체엔 @Transactional을 붙이지 않는다 - destinationRepository.findById()는
    // Spring Data JPA가 리포지토리 메서드 단위로 이미 자체 트랜잭션을 걸어주므로 DB 조회는 짧게 끝나고
    // 커넥션이 바로 반납된다. 여기에 @Transactional을 붙이면 뒤이어 실행되는 openMeteoClient의
    // 외부 HTTP 호출(최대 10초 read timeout)까지 같은 트랜잭션에 묶여서 그 시간만큼 DB 커넥션 풀에서
    // 커넥션 하나를 계속 붙잡고 있게 된다 - 트래픽이 몰릴 때 커넥션 풀 고갈로 이어질 수 있는 구조적 리스크.
    public WeatherResponse getWeather(WeatherRequest request) {
        Destination destination = destinationRepository.findById(request.destinationId())
                .orElseThrow(DestinationNotFoundException::new);

        List<WeatherDayDto> days;
        try {
            days = openMeteoClient.getDailyWeather(
                    destination.getLatitude(), destination.getLongitude(),
                    request.startDate(), request.endDate());
        } catch (OpenMeteoCallFailedException e) {
            log.warn("Open-Meteo 호출 실패, 계절 평균으로 대체합니다: {}", e.getMessage());
            return buildFallbackResponse(request.startDate(), request.endDate());
        }

        return buildResponse(days);
    }

    private WeatherResponse buildResponse(List<WeatherDayDto> days) {
        // "평균"은 최저/최고와 같은 지표(실제 기온)로 낸다 - 체감온도(feelsLike)를 평균에 섞으면
        // 습도 등으로 체감온도가 실제 최고기온보다 높은 날엔 "평균이 최고보다 높은" 모순이 생긴다.
        // 체감온도는 일자별 weather[].feelsLike로 여전히 확인 가능하므로 정보 손실은 없다.
        double avgMinTemp = days.stream().mapToDouble(WeatherDayDto::tempMin).average().orElse(15.0);
        double avgMaxTemp = days.stream().mapToDouble(WeatherDayDto::tempMax).average().orElse(15.0);
        double avgTemp = (avgMinTemp + avgMaxTemp) / 2;
        double minTemp = days.stream().mapToDouble(WeatherDayDto::tempMin).min().orElse(15.0);
        double maxTemp = days.stream().mapToDouble(WeatherDayDto::tempMax).max().orElse(15.0);

        String recommendation = String.format(
                "평균 기온 %.1f°C (최저 %.1f°C / 최고 %.1f°C)", avgTemp, minTemp, maxTemp);

        return new WeatherResponse(days, recommendation);
    }

    private WeatherResponse buildFallbackResponse(LocalDate startDate, LocalDate endDate) {
        List<WeatherDayDto> days = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            days.add(buildFallbackDay(date));
        }
        return new WeatherResponse(days, "날씨 정보를 가져올 수 없어 시즌 평균 기온으로 대체합니다.");
    }

    private WeatherDayDto buildFallbackDay(LocalDate date) {
        int month = date.getMonthValue();
        double temp;
        String description;
        if (month >= 6 && month <= 8) {
            temp = 28;
            description = "더운 여름 날씨";
        } else if (month >= 3 && month <= 5) {
            temp = 18;
            description = "따뜻한 봄 날씨";
        } else if (month >= 9 && month <= 11) {
            temp = 15;
            description = "선선한 가을 날씨";
        } else {
            temp = 5;
            description = "추운 겨울 날씨";
        }

        return new WeatherDayDto(date, description, temp - 2, temp + 2, temp, WeatherDataSource.SEASONAL_AVERAGE);
    }
}
