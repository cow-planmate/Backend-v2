package com.planmate.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * 분산 스케줄러 락. 인스턴스가 2개 이상이면 {@code @Scheduled} 는 노드마다 각자 돌기 때문에
 * 정리·집계 잡이 중복 실행된다. {@code @SchedulerLock} 이 붙은 잡만 이 락을 거쳐 한 노드에서만 돈다.
 *
 * <p>노드마다 돌아야 하는 잡(예: SSE 하트비트 — 자기 노드에 붙은 emitter 만 안다)에는 붙이지 않는다.
 * DB 커넥션 단위로 이미 직렬화되는 잡(outbox publisher 의 {@code FOR UPDATE SKIP LOCKED})도 마찬가지다.
 *
 * <p>{@code usingDbTime()} 을 쓰는 이유: 락 만료 판단을 앱 서버 시계가 아니라 DB 시계로 한다.
 * 노드 간 시계가 어긋나도 락이 조기 만료되지 않는다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
