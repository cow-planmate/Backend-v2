package com.planmate.domain.chatbot.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * planmate-ai 챗봇 서버 전용 {@link RestClient}.
 *
 * <p>기존 {@code googlePlacesRestTemplate}(3s/5s)을 재사용하지 않는다 — 챗봇 왕복은 Gemini 호출이라
 * 2~20초, planmate-ai 내부 데드라인이 30초라서 read timeout 을 40초로 넉넉히 잡는다.
 *
 * <p>{@link JdkClientHttpRequestFactory} 를 쓰는 이유: connect timeout 과 read timeout 이
 * 각각 {@code HttpConnectTimeoutException} / {@code HttpTimeoutException} 으로 구분돼 던져져서
 * "연결 실패만 1회 재시도"(설계 §5-3)를 신뢰성 있게 판별할 수 있다.
 *
 * <p>설계: project_ai/04_기능/ai/06_챗봇_연동_설계.md §8.
 */
@Configuration
public class AiChatbotClientConfig {

    @Bean
    @Qualifier("aiRestClient")
    public RestClient aiRestClient(
            RestClient.Builder builder,
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.internal-token}") String internalToken,
            @Value("${ai.timeout.connect-ms:5000}") long connectMs,
            @Value("${ai.timeout.read-ms:40000}") long readMs) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readMs));

        return builder
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .requestFactory(factory)
                .build();
    }
}
