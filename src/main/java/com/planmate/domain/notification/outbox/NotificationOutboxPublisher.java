package com.planmate.domain.notification.outbox;

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

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOutboxPublisher {
    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, byte[]> kafka;

    @Value("${notification.kafka.topic:planmate.notification.requested.v1}")
    private String topic;

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
            } catch (Exception exception) {
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
