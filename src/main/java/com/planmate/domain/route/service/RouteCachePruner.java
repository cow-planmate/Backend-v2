package com.planmate.domain.route.service;

import com.planmate.domain.route.repository.RouteCacheRepository;
import com.planmate.domain.route.repository.RouteTransitLaneCacheRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 길찾기 캐시 행을 지운다. TTL(만료 여부)은 조회 시점에 항상 필터링되므로,
 * 이 배치가 하루 늦게 돌아도 정확성엔 영향이 없다 — 순수 저장공간 정리 목적.
 * {@link com.planmate.domain.user.internal.outbox.UserOutboxPruner}와 동일한 패턴
 * (트래픽이 가장 적은 새벽, 실패해도 다음 날 다시 돌면 되므로 재시도하지 않음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteCachePruner {

    private final RouteCacheRepository routeCacheRepository;
    private final RouteTransitLaneCacheRepository laneCacheRepository;

    @Scheduled(cron = "0 0 4 * * *")
    @SchedulerLock(name = "routeCachePruner", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void prune() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int deleted = routeCacheRepository.deleteExpired(now) + laneCacheRepository.deleteExpired(now);
            if (deleted > 0) {
                log.info("길찾기 캐시 정리 완료 ({}건 삭제)", deleted);
            }
        } catch (Exception e) {
            log.warn("길찾기 캐시 정리 실패: {}", e.getMessage());
        }
    }
}
