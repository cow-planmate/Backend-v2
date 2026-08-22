package com.planmate.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Google Place 사진 다운로드+WebP 변환+MinIO 업로드 전용 스레드풀.
// project_ai/04_기능/place/06_키워드검색_API_인프라설계.md 4-1절.
@Configuration
public class GoogleSearchExecutorConfig {

    @Bean(name = "googlePlaceImageExecutor")
    public Executor googlePlaceImageExecutor() {
        // I/O(다운로드/업로드)와 CPU(이미지 인코딩)를 섞어 쓰므로 TourAPI(순수 I/O, 16)보다는
        // 작게, AsyncConfig 기본(5/10)보다는 여유 있게 잡음(잠정값).
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("google-place-image-");
        executor.initialize();
        return executor;
    }
}
