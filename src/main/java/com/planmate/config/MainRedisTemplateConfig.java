package com.planmate.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// 클래스명을 RedisConfig가 아니라 MainRedisTemplateConfig로 둔 이유: SharedSync 내부에도
// com.sharedsync.shared.config.RedisConfig가 있어서, 클래스명이 같으면 Spring의 기본 빈 이름
// 부여 규칙(클래스명 첫 글자만 소문자화)이 "redisConfig"로 정확히 충돌해 부팅이 실패한다
// (컴포넌트 스캔 단계의 충돌이라 @ConditionalOnMissingBean 오버라이드가 작동해볼 기회도 없음).
@Configuration
public class MainRedisTemplateConfig {

    // pubSubRedisConnectionFactory(RedisConnectionConfig)가 추가되면서 RedisConnectionFactory 빈이
    // 2개가 됨 — Boot 자동구성 빈("redisConnectionFactory")을 이름으로 명시해 모호성을 없앤다.
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            @Qualifier("redisConnectionFactory") RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
