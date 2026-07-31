package com.planmate.domain.weather.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.weather.dto.WeatherDataSource;
import com.planmate.domain.weather.dto.WeatherResponse;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 DB(destination 시드 데이터)는 그대로 쓰고, 외부 HTTP 호출(Open-Meteo)만 목킹한다.
// OAuthControllerTest의 "RestTemplate만 목킹, 나머지는 실제로 붙는다" 패턴과 동일.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WeatherControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DestinationRepository destinationRepository;

    @MockitoBean
    private RestTemplate openMeteoRestTemplate;

    private Integer seoulDestinationId() {
        Destination seoul = destinationRepository.findByDestinationName("서울").orElseThrow();
        return seoul.getDestinationId();
    }

    private String dailyJson(String date, int code, double tempMax, double tempMin, double feelsLike) {
        return "{\"daily\":{"
                + "\"time\":[\"" + date + "\"],"
                + "\"weathercode\":[" + code + "],"
                + "\"temperature_2m_max\":[" + tempMax + "],"
                + "\"temperature_2m_min\":[" + tempMin + "],"
                + "\"apparent_temperature_max\":[" + feelsLike + "]"
                + "}}";
    }

    @Test
    @DisplayName("실제 destination 시드 데이터(서울) + 목데이터로 정상 날씨 조회 성공")
    void getWeather_success_realDestinationMockedOpenMeteo() throws Exception {
        Integer destinationId = seoulDestinationId();
        LocalDate date = LocalDate.now().plusDays(1);

        given(openMeteoRestTemplate.getForEntity(anyString(), eq(String.class)))
                .willReturn(new ResponseEntity<>(dailyJson(date.toString(), 0, 30.4, 22.6, 33.1), HttpStatus.OK));

        String body = mockMvc.perform(get("/api/weather")
                        .param("destinationId", String.valueOf(destinationId))
                        .param("startDate", date.toString())
                        .param("endDate", date.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        WeatherResponse response = objectMapper.readValue(body, WeatherResponse.class);
        assertThat(response.weather()).hasSize(1);
        assertThat(response.weather().get(0).date()).isEqualTo(date);
        assertThat(response.weather().get(0).description()).isEqualTo("맑음");
        assertThat(response.weather().get(0).tempMin()).isEqualTo(23.0);
        assertThat(response.weather().get(0).tempMax()).isEqualTo(30.0);
        assertThat(response.weather().get(0).feelsLike()).isEqualTo(33.0);
        assertThat(response.weather().get(0).dataSource()).isEqualTo(WeatherDataSource.FORECAST);
    }

    @Test
    @DisplayName("존재하지 않는 destinationId면 404 DEST_001을 반환한다")
    void getWeather_destinationNotFound_returns404() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);

        mockMvc.perform(get("/api/weather")
                        .param("destinationId", "999999")
                        .param("startDate", date.toString())
                        .param("endDate", date.toString()))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("DEST_001"));
    }

    @Test
    @DisplayName("필수 쿼리파라미터(startDate)가 없으면 400을 반환한다")
    void getWeather_missingStartDate_returns400() throws Exception {
        Integer destinationId = seoulDestinationId();

        mockMvc.perform(get("/api/weather")
                        .param("destinationId", String.valueOf(destinationId))
                        .param("endDate", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("endDate가 startDate보다 빠르면 400을 반환한다")
    void getWeather_endBeforeStart_returns400() throws Exception {
        Integer destinationId = seoulDestinationId();
        LocalDate start = LocalDate.now().plusDays(5);
        LocalDate end = LocalDate.now().plusDays(1);

        mockMvc.perform(get("/api/weather")
                        .param("destinationId", String.valueOf(destinationId))
                        .param("startDate", start.toString())
                        .param("endDate", end.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Open-Meteo 호출이 실패해도 예외 없이 200과 계절 평균 폴백으로 응답한다")
    void getWeather_openMeteoFails_returnsSeasonalAverageFallback() throws Exception {
        Integer destinationId = seoulDestinationId();
        LocalDate date = LocalDate.now().plusDays(1);

        given(openMeteoRestTemplate.getForEntity(anyString(), eq(String.class)))
                .willReturn(new ResponseEntity<>("server error", HttpStatus.INTERNAL_SERVER_ERROR));

        String body = mockMvc.perform(get("/api/weather")
                        .param("destinationId", String.valueOf(destinationId))
                        .param("startDate", date.toString())
                        .param("endDate", date.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        WeatherResponse response = objectMapper.readValue(body, WeatherResponse.class);
        assertThat(response.recommendation()).isEqualTo("날씨 정보를 가져올 수 없어 시즌 평균 기온으로 대체합니다.");
        assertThat(response.weather()).hasSize(1);
        assertThat(response.weather().get(0).dataSource()).isEqualTo(WeatherDataSource.SEASONAL_AVERAGE);
    }
}
