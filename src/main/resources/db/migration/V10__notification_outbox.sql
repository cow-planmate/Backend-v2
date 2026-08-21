CREATE TABLE notification_outbox (
    event_id UUID PRIMARY KEY,
    recipient_user_id UUID NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    locked_at TIMESTAMPTZ,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT
);

CREATE INDEX ix_notification_outbox_due
    ON notification_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
CREATE INDEX ix_notification_outbox_published ON notification_outbox (published_at)
    WHERE published_at IS NOT NULL;
