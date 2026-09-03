package com.planmate.domain.user.internal.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 보존 기간이 지난 outbox 행을 지운다.
 *
 * 지워진 구간이 필요한 소비자가 있어도 유실이 아니다 — 커서가 보존 창 밖이면 서버가 전체
 * 스냅샷으로 격하시켜 복구한다. 따라서 보존 기간은 "정합성 마지노선"이 아니라
 * "스냅샷 재전송 없이 따라잡을 수 있는 최대 정지 시간"이다.
 *
 * 동시에 이 값은 탈퇴자의 옛 닉네임이 이 테이블에 남아 있는 최대 기간이기도 하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserOutboxPruner {

    private final UserOutboxRepository outboxRepository;
    private final UserOutboxProperties properties;

    /** 트래픽이 가장 적은 새벽에. 실패해도 다음 날 다시 돌면 되므로 재시도하지 않는다. */
    @Scheduled(cron = "0 0 4 * * *")
    @SchedulerLock(name = "userOutboxPruner", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void prune() {
        try {
            int deleted = outboxRepository.deleteOlderThan(Duration.ofDays(properties.getRetentionDays()));
            if (deleted > 0) {
                log.info("사용자 outbox 정리 완료 ({}일 경과 {}건 삭제)", properties.getRetentionDays(), deleted);
            }
        } catch (Exception e) {
            // 정리 실패는 기능에 영향이 없다(테이블이 조금 커질 뿐) — 기동/운영을 막지 않는다
            log.warn("사용자 outbox 정리 실패: {}", e.getMessage());
        }
    }
}
