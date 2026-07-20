package com.planmate.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// TourAPI 다중 호출(지역코드×선호테마 조합) 병렬 실행 전용 스레드풀.
// project_ai/04_기능/place/04_추천장소_API_인프라설계.md 3-4절.
@Configuration
public class TourApiExecutorConfig {

    @Bean
    public ExecutorService tourApiExecutor() {
        // I/O 대기가 대부분이라 CPU 코어 수보다 넉넉하게 고정 크기로 잡음(잠정값).
        return Executors.newFixedThreadPool(16);
    }
}
