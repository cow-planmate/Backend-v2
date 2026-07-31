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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {

    private static final Integer DESTINATION_ID = 1;
    private static final BigDecimal SEOUL_LAT = new BigDecimal("37.56330000");
    private static final BigDecimal SEOUL_LNG = new BigDecimal("126.97970000");

    @Mock
    private DestinationRepository destinationRepository;
    @Mock
    private OpenMeteoClient openMeteoClient;

    private WeatherService weatherService;

    @BeforeEach
    void setUp() {
        weatherService = new WeatherService(destinationRepository, openMeteoClient);
    }

    private Destination seoul() {
        return Destination.builder()
                .destinationId(DESTINATION_ID)
                .destinationName("서울")
                .latitude(SEOUL_LAT)
                .longitude(SEOUL_LNG)
                .build();
    }

    @Test
    @DisplayName("존재하지 않는 destinationId면 DestinationNotFoundException을 던지고 OpenMeteoClient는 호출하지 않는다")
    void getWeather_destinationNotFound_throwsException() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.empty());

        WeatherRequest request = new WeatherRequest(DESTINATION_ID, LocalDate.now(), LocalDate.now().plusDays(1));

        assertThatThrownBy(() -> weatherService.getWeather(request))
                .isInstanceOf(DestinationNotFoundException.class);

        verify(openMeteoClient, never()).getDailyWeather(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Open-Meteo 호출이 실패하면 예외를 전파하지 않고 계절 평균 폴백으로 응답한다")
    void getWeather_openMeteoFails_returnsSeasonalAverageFallback() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(seoul()));
        given(openMeteoClient.getDailyWeather(eq(SEOUL_LAT), eq(SEOUL_LNG), any(), any()))
                .willThrow(new OpenMeteoCallFailedException("timeout"));

        LocalDate julyDay = LocalDate.of(LocalDate.now().getYear(), Month.JULY, 10);
        WeatherRequest request = new WeatherRequest(DESTINATION_ID, julyDay, julyDay);

        WeatherResponse response = weatherService.getWeather(request);

        assertThat(response.recommendation()).isEqualTo("날씨 정보를 가져올 수 없어 시즌 평균 기온으로 대체합니다.");
        assertThat(response.weather()).hasSize(1);
        WeatherDayDto day = response.weather().get(0);
        assertThat(day.date()).isEqualTo(julyDay);
        assertThat(day.description()).isEqualTo("더운 여름 날씨");
        assertThat(day.tempMin()).isEqualTo(26.0);
        assertThat(day.tempMax()).isEqualTo(30.0);
        assertThat(day.feelsLike()).isEqualTo(28.0);
        assertThat(day.dataSource()).isEqualTo(WeatherDataSource.SEASONAL_AVERAGE);
    }

    @Test
    @DisplayName("계절 평균 폴백은 요청 기간(start~end)의 모든 날짜를 빠짐없이 채운다")
    void getWeather_fallback_coversEntireDateRange() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(seoul()));
        given(openMeteoClient.getDailyWeather(eq(SEOUL_LAT), eq(SEOUL_LNG), any(), any()))
                .willThrow(new OpenMeteoCallFailedException("timeout"));

        LocalDate start = LocalDate.of(2026, Month.DECEMBER, 30);
        LocalDate end = LocalDate.of(2027, Month.JANUARY, 2);
        WeatherRequest request = new WeatherRequest(DESTINATION_ID, start, end);

        WeatherResponse response = weatherService.getWeather(request);

        assertThat(response.weather()).hasSize(4);
        assertThat(response.weather()).extracting(WeatherDayDto::date)
                .containsExactly(start, start.plusDays(1), start.plusDays(2), start.plusDays(3));
        assertThat(response.weather()).allMatch(day -> day.description().equals("추운 겨울 날씨"));
    }

    @Test
    @DisplayName("Open-Meteo 호출이 성공하면 반환된 일자별 데이터를 그대로 담고, 평균/최저/최고 기준 추천 문구를 만든다")
    void getWeather_success_buildsResponseFromClientResult() {
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(seoul()));

        LocalDate day1 = LocalDate.now().plusDays(1);
        LocalDate day2 = LocalDate.now().plusDays(2);
        List<WeatherDayDto> days = List.of(
                new WeatherDayDto(day1, "맑음", 20.0, 28.0, 26.0, WeatherDataSource.FORECAST),
                new WeatherDayDto(day2, "비", 18.0, 24.0, 22.0, WeatherDataSource.FORECAST)
        );
        given(openMeteoClient.getDailyWeather(eq(SEOUL_LAT), eq(SEOUL_LNG), eq(day1), eq(day2)))
                .willReturn(days);

        WeatherRequest request = new WeatherRequest(DESTINATION_ID, day1, day2);
        WeatherResponse response = weatherService.getWeather(request);

        assertThat(response.weather()).isEqualTo(days);
        // 평균은 체감온도가 아니라 실제 기온(tempMin/tempMax) 기준: avgMin=(20+18)/2=19.0, avgMax=(28+24)/2=26.0, avg=(19.0+26.0)/2=22.5
        // 최저 18.0(day1/day2 tempMin 중 최소), 최고 28.0(day1/day2 tempMax 중 최대)
        assertThat(response.recommendation()).isEqualTo("평균 기온 22.5°C (최저 18.0°C / 최고 28.0°C)");
    }
}
