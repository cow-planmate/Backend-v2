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
 * Sentinel 프로퍼티가 채워지면 코드 변경 없이 Sentinel 기반 HA 경로로 전환된다.
 * Sentinel 모드에서도 모든 팩토리가 마스터를 읽고 쓴다 — 읽기 분산은 하지 않는다
 * (이유는 buildReadWriteConnectionFactory 주석 참고). Pub/Sub 은 발행이 반드시
 * 마스터로 가야 해서 애초에 분산 대상이 아니다.
 *
 * SharedSync 자체 RedisConfig(sharedSyncRedisConnectionFactory/pubSubConnectionFactory)는
 * Sentinel 디스커버리를 지원하지 않는다 — 두 빈 다 @ConditionalOnMissingBean(name=...)로 걸려 있어서
 * 이 클래스가 같은 이름으로 먼저 등록해두면 SharedSync 기본 구현이 자동으로 물러난다. 별도 클래스로
 * 안 뺀 이유: v1의 PlanMateRedisConfig가 이미 검증한 패턴 — Sentinel 파싱 로직(sentinelConfiguration())을
 * 하나만 두고 메인/SharedSync 커넥션 팩토리 전부가 재사용한다.
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
        return buildReadWriteConnectionFactory();
    }

    @Bean(name = "pubSubRedisConnectionFactory")
    public RedisConnectionFactory pubSubRedisConnectionFactory() {
        return buildPubSubConnectionFactory();
    }

    /**
     * SharedSync 프레임워크가 @Qualifier("sharedSyncRedisConnectionFactory")로 참조하는 커넥션 팩토리.
     * 프레임워크의 @ConditionalOnMissingBean 기본(비-Sentinel) 팩토리 대신 이 Sentinel-aware 팩토리가 쓰인다.
     */
    @Bean(name = "sharedSyncRedisConnectionFactory")
    public RedisConnectionFactory sharedSyncRedisConnectionFactory() {
        return buildReadWriteConnectionFactory();
    }

    /**
     * SharedSync 프레임워크가 @Qualifier("pubSubConnectionFactory")로 참조하는 Pub/Sub 커넥션 팩토리.
     * WebSocket 다중 서버 동기화(RedisSyncService)가 이 팩토리로 발행하므로 Sentinel-aware여야 한다.
     */
    @Bean(name = "pubSubConnectionFactory")
    public RedisConnectionFactory pubSubConnectionFactory() {
        return buildPubSubConnectionFactory();
    }

    /**
     * 요청 처리용 메인(읽기/쓰기) 커넥션 팩토리.
     * Sentinel 모드에서도 읽기를 레플리카로 분산하지 않는다(Lettuce 기본 = 마스터).
     *
     * <p>레플리카 분산을 뺀 이유: 이 팩토리로 읽는 데이터가 전부 "쓰고 곧바로 읽는" 것들이다.
     * OAuth 로그인 코드는 발급 직후 리다이렉트에서 교환되고(수백 ms), 리프레시 토큰은 회전 시
     * 삭제 직후 검증되며, 이메일 인증코드도 마찬가지다. 복제는 비동기라 레플리카에서 읽으면
     * 방금 쓴 값이 아직 없거나 지운 값이 남아 있어 로그인이 간헐적으로 실패한다.
     * SharedSync 협업편집 상태(sharedSyncRedisConnectionFactory)도 같은 이유로 마스터를 읽어야 한다.
     *
     * <p>Pub/Sub 커넥션과는 블로킹 구독이 메인 풀을 잠식하지 않도록 인스턴스를 분리한다.
     */
    private RedisConnectionFactory buildReadWriteConnectionFactory() {
        if (sentinelEnabled()) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinelConfiguration());
            factory.afterPropertiesSet();
            return factory;
        }
        return buildStandaloneConnectionFactory();
    }

    /**
     * Pub/Sub 전용 커넥션 팩토리(SSE 알림).
     * Sentinel 모드에서도 마스터에만 연결한다 — 발행(PUBLISH)은 반드시 마스터로 가야 하므로
     * 읽기 분산(readFrom)을 적용하지 않는다.
     */
    private RedisConnectionFactory buildPubSubConnectionFactory() {
        if (sentinelEnabled()) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinelConfiguration());
            factory.afterPropertiesSet();
            return factory;
        }
        return buildStandaloneConnectionFactory();
    }

    /** Sentinel 프로퍼티가 없을 때의 단일 노드 폴백(두 팩토리 공통). */
    private RedisConnectionFactory buildStandaloneConnectionFactory() {
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
