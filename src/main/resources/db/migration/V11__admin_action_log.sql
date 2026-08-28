-- ------------------------------------------------------------
-- 계정 정지 + 관리자 액션 감사 로그
-- ------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN suspended_until TIMESTAMP NULL,
    ADD COLUMN suspend_reason  VARCHAR(255) NULL;

CREATE TABLE admin_action_log (
    log_id         BIGINT       GENERATED ALWAYS AS IDENTITY,
    admin_user_id  UUID         NOT NULL,
    action_type    VARCHAR(50)  NOT NULL,   -- SUSPEND_USER / UNSUSPEND_USER
    target_type    VARCHAR(30)  NOT NULL,   -- USER
    target_id      VARCHAR(64)  NOT NULL,
    reason         TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_admin_action_log PRIMARY KEY (log_id)
);
