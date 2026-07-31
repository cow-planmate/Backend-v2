package com.planmate.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // TourApiClient 전용 — ODsay 등 기존 공용 restTemplate()은 타임아웃 미설정 상태를 그대로 유지하고
    // 이 빈만 별도로 타임아웃을 둔다(연결 3초/읽기 5초, project_ai/04_기능/place/04_추천장소_API_인프라설계.md 3-3절).
    @Bean
    public RestTemplate tourApiRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    // OpenMeteoClient 전용 — 날씨 조회는 실패 시 예외 없이 폴백(계절 평균)으로 응답하는 정책이라
    // 너무 짧은 타임아웃은 정상 응답 중인데도 오탐 폴백을 유발할 수 있어, tourApiRestTemplate(3초/5초)보다
    // 여유 있게 잡는다(연결 5초/읽기 10초, project_ai/04_기능/날씨/A_추천_조회.md 10-3절).
    @Bean
    public RestTemplate openMeteoRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }
}
