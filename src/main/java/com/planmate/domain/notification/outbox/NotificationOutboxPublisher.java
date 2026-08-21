package com.planmate.domain.notification.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOutboxPublisher {
    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, byte[]> kafka;

    @Value("${notification.kafka.topic:planmate.notification.requested.v1}")
    private String topic;

    private final MeterRegistry meters;

    /**
     * 아직 Kafka 로 나가지 못한 outbox 행 수. 이 설계의 전제는 "브로커가 죽어도 업무는
     * 성공하고 알림만 밀린다" 인데, 밀린 것을 볼 수 없으면 그 전제가 장애를 숨기는 쪽으로만
     * 작동한다. 조회는 비싸므로 발행 주기(0.5초)가 아니라 별도 주기로 갱신한다.
     */
    private final AtomicLong pendingCount = new AtomicLong();

    @PostConstruct
    void bindMetrics() {
        Gauge.builder("planmate.notification.outbox.pending", pendingCount, AtomicLong::get)
                .description("Kafka 로 아직 발행되지 않은 알림 outbox 행 수")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${notification.outbox.gauge-interval-ms:30000}")
    public void refreshPendingGauge() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE published_at IS NULL", Long.class);
        pendingCount.set(count == null ? 0L : count);
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval-ms:500}")
    public void publishDue() {
        for (Entry entry : claim(100)) {
            try {
                kafka.send(topic, entry.recipientUserId().toString(), entry.payload()).get(10, TimeUnit.SECONDS);
                jdbcTemplate.update("""
                        UPDATE notification_outbox
                        SET published_at = now(), locked_at = NULL, last_error = NULL
                        WHERE event_id = ?
                        """, entry.eventId());
                meters.counter("planmate.notification.outbox.published").increment();
                // 비동기 경계의 상관 키. 소비자(Notification)가 같은 event_id 를 MDC 에 싣는다.
                log.debug("notification outbox published eventId={}", entry.eventId());
            } catch (Exception exception) {
                meters.counter("planmate.notification.outbox.publish.failed").increment();
                Duration delay = Duration.ofSeconds(Math.min(300, 1L << Math.min(entry.attemptCount(), 8)));
                jdbcTemplate.update("""
                        UPDATE notification_outbox
                        SET locked_at = NULL,
                            next_attempt_at = now() + (? * interval '1 millisecond'),
                            last_error = ?
                        WHERE event_id = ?
                        """, delay.toMillis(), rootMessage(exception), entry.eventId());
            }
        }
    }

    @Transactional
    public List<Entry> claim(int limit) {
        return jdbcTemplate.query("""
                WITH due AS (
                    SELECT event_id
                    FROM notification_outbox
                    WHERE published_at IS NULL AND next_attempt_at <= now()
                      AND (locked_at IS NULL OR locked_at < now() - interval '5 minutes')
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE notification_outbox o
                SET locked_at = now(), attempt_count = attempt_count + 1
                FROM due
                WHERE o.event_id = due.event_id
                RETURNING o.event_id, o.recipient_user_id, o.payload, o.attempt_count
                """, (rs, rowNum) -> new Entry(
                rs.getObject("event_id", UUID.class), rs.getObject("recipient_user_id", UUID.class),
                rs.getBytes("payload"), rs.getInt("attempt_count")), limit);
    }

    @Scheduled(cron = "0 35 4 * * *", zone = "UTC")
    public void prune() {
        jdbcTemplate.update("DELETE FROM notification_outbox WHERE published_at < now() - interval '30 days'");
    }

    private static String rootMessage(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message.substring(0, Math.min(2000, message.length()));
    }

    record Entry(UUID eventId, UUID recipientUserId, byte[] payload, int attemptCount) {}
}
