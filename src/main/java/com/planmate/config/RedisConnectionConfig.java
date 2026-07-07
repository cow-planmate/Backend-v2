package com.planmate.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.StringUtils;

import com.planmate.common.sse.SseRedisSubscriber;

/**
 * Pub/Sub 전용 Redis 커넥션(SSE 알림)을 메인 커넥션 풀과 분리한다.
 * Sentinel 프로퍼티가 채워지면 코드 변경 없이 Sentinel 경로로 전환된다(현재는 비활성 — 8절 TODO).
 */
@Configuration
public class RedisConnectionConfig {

    /** Redis Pub/Sub 채널명 — SSE 알림 전용. */
    public static final String SSE_CHANNEL = "sse:notifications";

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.sentinel.master:}")
    private String sentinelMaster;

    @Value("${spring.data.redis.sentinel.nodes:}")
    private String sentinelNodes;

    @Value("${spring.data.redis.sentinel.password:${spring.data.redis.password:}}")
    private String sentinelPassword;

    private boolean sentinelEnabled() {
        return StringUtils.hasText(sentinelMaster) && StringUtils.hasText(sentinelNodes);
    }

    private RedisSentinelConfiguration sentinelConfiguration() {
        RedisSentinelConfiguration config = new RedisSentinelConfiguration();
        config.setMaster(sentinelMaster.trim());
        for (String node : sentinelNodes.split(",")) {
            String hostPort = node.trim();
            if (hostPort.isEmpty()) {
                continue;
            }
            int idx = hostPort.lastIndexOf(':');
            String sentinelHost = idx > 0 ? hostPort.substring(0, idx) : hostPort;
            int sentinelPort = idx > 0 ? Integer.parseInt(hostPort.substring(idx + 1)) : 26379;
            config.addSentinel(new RedisNode(sentinelHost, sentinelPort));
        }
        if (StringUtils.hasText(password)) {
            config.setPassword(RedisPassword.of(password));
        }
        if (StringUtils.hasText(sentinelPassword)) {
            config.setSentinelPassword(RedisPassword.of(sentinelPassword));
        }
        return config;
    }

    // Spring Boot의 Redis 자동구성(RedisConnectionFactory 자동 빈)은 타입 기준
    // @ConditionalOnMissingBean이라, 사용자 정의 RedisConnectionFactory 빈이 하나라도 있으면
    // (이름 무관) 통째로 비활성화된다. 그래서 pubSubRedisConnectionFactory만 추가하면 기존에
    // 암묵적으로 의존하던 Boot 자동구성 빈이 사라져버린다 — 메인 커넥션 팩토리도 직접 만들어
    // @Primary로 명시해야 한다(원본 PlanMateRedisConfig와 동일한 이유).
    @Bean(name = "redisConnectionFactory")
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return buildConnectionFactory();
    }

    @Bean(name = "pubSubRedisConnectionFactory")
    public RedisConnectionFactory pubSubRedisConnectionFactory() {
        return buildConnectionFactory();
    }

    /** 요청 처리용 메인 커넥션과 Pub/Sub 커넥션은 블로킹 구독이 메인 풀을 잠식하지 않도록 인스턴스를 분리한다. */
    private RedisConnectionFactory buildConnectionFactory() {
        if (sentinelEnabled()) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinelConfiguration());
            factory.afterPropertiesSet();
            return factory;
        }
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        if (StringUtils.hasText(password)) {
            standalone.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean(name = "sseStringRedisTemplate")
    public StringRedisTemplate sseStringRedisTemplate(
            @Qualifier("pubSubRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisMessageListenerContainer sseRedisListenerContainer(
            @Qualifier("pubSubRedisConnectionFactory") RedisConnectionFactory connectionFactory,
            SseRedisSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(SSE_CHANNEL));
        return container;
    }
}
