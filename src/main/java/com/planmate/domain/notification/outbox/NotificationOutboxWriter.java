package com.planmate.domain.notification.outbox;

import build.buf.gen.planmate.notification.v1.NotificationRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NotificationOutboxWriter {
    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(NotificationRequested event) {
        jdbcTemplate.update("""
                INSERT INTO notification_outbox (event_id, recipient_user_id, payload)
                VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, UUID.fromString(event.getEventId()), UUID.fromString(event.getRecipientUserId()),
                event.toByteArray());
    }
}
