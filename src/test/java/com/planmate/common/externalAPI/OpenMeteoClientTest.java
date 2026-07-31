package com.planmate.common.externalAPI;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.weather.dto.WeatherDataSource;
import com.planmate.domain.weather.dto.WeatherDayDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OpenMeteoClientTest {

    private static final BigDecimal SEOUL_LAT = new BigDecimal("37.56330000");
    private static final BigDecimal SEOUL_LNG = new BigDecimal("126.97970000");

    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenMeteoClient openMeteoClient;

    private void setUp() {
        openMeteoClient = new OpenMeteoClient(restTemplate, objectMapper);
    }

    private String dailyJson(List<String> dates, List<Integer> codes,
                              List<Double> tempMax, List<Double> tempMin, List<Double> feelsLike) {
        StringBuilder sb = new StringBuilder("{\"daily\":{");
        sb.append("\"time\":").append(toJsonArray(dates, true)).append(",");
        sb.append("\"weathercode\":").append(toJsonArray(codes, false)).append(",");
        sb.append("\"temperature_2m_max\":").append(toJsonArray(tempMax, false)).append(",");
        sb.append("\"temperature_2m_min\":").append(toJsonArray(tempMin, false)).append(",");
        sb.append("\"apparent_temperature_max\":").append(toJsonArray(feelsLike, false));
        sb.append("}}");
        return sb.toString();
    }

    private String toJsonArray(List<?> values, boolean quoted) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            if (quoted) {
                sb.append("\"").append(values.get(i)).append("\"");
            } else {
                sb.append(values.get(i));
            }
        }
        sb.append("]");
        return sb.toString();
    }

    @Test
    @DisplayName("오늘+15일 이내 요청은 forecast API를 호출하고 dataSource=FORECAST로 반환한다")
    void getDailyWeather_withinForecastLimit_usesForecastApi() {
        setUp();
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = LocalDate.now().plusDays(2);
        String body = dailyJson(
                List.of(start.toString(), end.toString()),
                List.of(0, 61),
                List.of(30.4, 25.6),
                List.of(22.4, 20.5),
                List.of(33.4, 27.6));
        given(restTemplate.getForEntity(anyString(), eq(String.class)))
                .willReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<WeatherDayDto> result = openMeteoClient.getDailyWeather(SEOUL_LAT, SEOUL_LNG, start, end);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(restTemplate).getForEntity(urlCaptor.capture(), eq(String.class));
        assertThat(urlCaptor.getValue()).startsWith("https://api.open-meteo.com/v1/forecast");
        assertThat(urlCaptor.getValue()).doesNotContain("archive-api");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).date()).isEqualTo(start);
        assertThat(result.get(0).description()).isEqualTo("맑음");
        assertThat(result.get(0).tempMin()).isEqualTo(22.0);
        assertThat(result.get(0).tempMax()).isEqualTo(30.0);
        assertThat(result.get(0).feelsLike()).isEqualTo(33.0);
        assertThat(result.get(0).dataSource()).isEqualTo(WeatherDataSource.FORECAST);
        assertThat(result.get(1).description()).isEqualTo("비");
        assertThat(result.get(1).dataSource()).isEqualTo(WeatherDataSource.FORECAST);
    }

    @Test
    @DisplayName("오늘+15일을 넘는 요청은 archive API(작년 동기간)를 호출하고, 응답 날짜를 +1년 해서 올해로 매핑하며 dataSource=LAST_YEAR_ACTUAL로 반환한다")
    void getDailyWeather_beyondForecastLimit_usesArchiveApiAndRemapsYear() {
        setUp();
        LocalDate start = LocalDate.now().plusDays(20);
        LocalDate end = LocalDate.now().plusDays(21);
        LocalDate archiveStart = start.minusYears(1);
        LocalDate archiveEnd = end.minusYears(1);

        String body = dailyJson(
                List.of(archiveStart.toString(), archiveEnd.toString()),
                List.of(95, 3),
                List.of(20.0, 18.0),
                List.of(15.0, 12.0),
                List.of(19.0, 16.0));
        given(restTemplate.getForEntity(anyString(), eq(String.class)))
                .willReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<WeatherDayDto> result = openMeteoClient.getDailyWeather(SEOUL_LAT, SEOUL_LNG, start, end);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(restTemplate).getForEntity(urlCaptor.capture(), eq(String.class));
        assertThat(urlCaptor.getValue()).startsWith("https://archive-api.open-meteo.com/v1/archive");
        assertThat(urlCaptor.getValue()).contains("start_date=" + archiveStart);
        assertThat(urlCaptor.getValue()).contains("end_date=" + archiveEnd);

        assertThat(result).hasSize(2);
        // 응답으로 온 작년 날짜(archiveStart)가 올해 날짜(start)로 다시 매핑돼야 한다
        assertThat(result.get(0).date()).isEqualTo(start);
        assertThat(result.get(1).date()).isEqualTo(end);
        assertThat(result.get(0).description()).isEqualTo("뇌우");
        assertThat(result.get(0).dataSource()).isEqualTo(WeatherDataSource.LAST_YEAR_ACTUAL);
        assertThat(result.get(1).dataSource()).isEqualTo(WeatherDataSource.LAST_YEAR_ACTUAL);
    }

    @Test
    @DisplayName("Open-Meteo가 비2xx 응답을 반환하면 OpenMeteoCallFailedException을 던진다")
    void getDailyWeather_non2xxResponse_throwsException() {
        setUp();
        given(restTemplate.getForEntity(anyString(), eq(String.class)))
                .willReturn(new ResponseEntity<>("error", HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> openMeteoClient.getDailyWeather(
                SEOUL_LAT, SEOUL_LNG, LocalDate.now(), LocalDate.now().plusDays(1)))
                .isInstanceOf(OpenMeteoCallFailedException.class);
    }

    @Test
    @DisplayName("RestTemplate 호출 자체가 예외를 던지면(네트워크 오류 등) OpenMeteoCallFailedException으로 감싸 던진다")
    void getDailyWeather_networkException_wrapsException() {
        setUp();
        given(restTemplate.getForEntity(anyString(), eq(String.class)))
                .willThrow(new org.springframework.web.client.ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> openMeteoClient.getDailyWeather(
                SEOUL_LAT, SEOUL_LNG, LocalDate.now(), LocalDate.now().plusDays(1)))
                .isInstanceOf(OpenMeteoCallFailedException.class);
    }

    @Test
    @DisplayName("응답 JSON이 daily 필드를 포함하지 않는 등 파싱이 불가능하면 OpenMeteoCallFailedException을 던진다")
    void getDailyWeather_malformedJson_throwsException() {
        setUp();
        given(restTemplate.getForEntity(anyString(), eq(String.class)))
                .willReturn(new ResponseEntity<>("{\"unexpected\":true}", HttpStatus.OK));

        assertThatThrownBy(() -> openMeteoClient.getDailyWeather(
                SEOUL_LAT, SEOUL_LNG, LocalDate.now(), LocalDate.now().plusDays(1)))
                .isInstanceOf(OpenMeteoCallFailedException.class);
    }

    @Test
    @DisplayName("정의되지 않은 weathercode는 '정보 없음'으로 매핑한다")
    void getDailyWeather_unknownWeatherCode_mapsToUnknownDescription() {
        setUp();
        LocalDate date = LocalDate.now().plusDays(1);
        String body = dailyJson(List.of(date.toString()), List.of(999), List.of(20.0), List.of(10.0), List.of(15.0));
        given(restTemplate.getForEntity(anyString(), eq(String.class)))
                .willReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<WeatherDayDto> result = openMeteoClient.getDailyWeather(SEOUL_LAT, SEOUL_LNG, date, date);

        assertThat(result.get(0).description()).isEqualTo("정보 없음");
    }
}
