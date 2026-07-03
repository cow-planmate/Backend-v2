-- ============================================================
-- PlanMate Backend-v2  V1__init.sql
-- PostgreSQL 14+
-- 원본(코드13 V1~V7) 대비 변경 내역은 설계 문서 Section 2.2 참고
-- ============================================================

-- ------------------------------------------------------------
-- 1. users
-- ------------------------------------------------------------
CREATE TABLE users (
    user_id     UUID         NOT NULL,
    provider    VARCHAR(20)  NOT NULL,
    provider_id VARCHAR(100),
    email       VARCHAR(255),
    password    VARCHAR(255),
    nickname    VARCHAR(100) NOT NULL,
    birthdate   DATE,
    gender      VARCHAR(10),
    fcm_token   TEXT,
    role        VARCHAR(10)  NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP,

    CONSTRAINT pk_users           PRIMARY KEY (user_id),
    CONSTRAINT chk_users_provider CHECK (provider IN ('LOCAL', 'KAKAO', 'GOOGLE', 'NAVER')),
    CONSTRAINT chk_users_gender   CHECK (gender   IN ('남성', '여성', '기타')),
    CONSTRAINT chk_users_role     CHECK (role     IN ('USER', 'ADMIN'))
);

-- 탈퇴 사용자 닉네임/이메일 재사용 허용 (소프트 삭제 연동)
CREATE UNIQUE INDEX uk_users_nickname
    ON users (nickname)
    WHERE deleted_at IS NULL;

-- 이메일 하나 = 계정 하나 (provider 무관, 원본 앱 로직에만 있던 정책을 DB 제약으로 승격 — 회원가입/OAuth 동시 요청 경합 방지)
CREATE UNIQUE INDEX uk_users_email
    ON users (LOWER(email))
    WHERE deleted_at IS NULL;

-- OAuth 중복 가입 방지 (탈퇴 후 전체 범위 — 기존 계정 복구 흐름 유도)
CREATE UNIQUE INDEX uk_users_provider_provider_id
    ON users (provider, provider_id);

-- ------------------------------------------------------------
-- 2. destination  (travel_category + travel 통합 대체)
-- ------------------------------------------------------------
CREATE TABLE destination (
    destination_id   INT          GENERATED ALWAYS AS IDENTITY,
    destination_name VARCHAR(100) NOT NULL,
    latitude         NUMERIC(10, 8),
    longitude        NUMERIC(11, 8),

    CONSTRAINT pk_destination      PRIMARY KEY (destination_id),
    CONSTRAINT uk_destination_name UNIQUE (destination_name)
);

-- ------------------------------------------------------------
-- 3. preferred_theme  (preferred_theme_category 테이블 통합)
-- ------------------------------------------------------------
CREATE TABLE preferred_theme (
    preferred_theme_id   INT          GENERATED ALWAYS AS IDENTITY,
    preferred_theme_name VARCHAR(100) NOT NULL,
    category             VARCHAR(20)  NOT NULL,

    CONSTRAINT pk_preferred_theme           PRIMARY KEY (preferred_theme_id),
    CONSTRAINT chk_preferred_theme_category CHECK (category IN ('관광지', '숙소', '식당'))
);

-- ------------------------------------------------------------
-- 4. user_preferred_theme
-- ------------------------------------------------------------
CREATE TABLE user_preferred_theme (
    user_preferred_theme_id BIGINT    GENERATED ALWAYS AS IDENTITY,
    user_id                 UUID      NOT NULL,
    preferred_theme_id      INT       NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_preferred_theme            PRIMARY KEY (user_preferred_theme_id),
    CONSTRAINT uk_user_preferred_theme_user_theme UNIQUE (user_id, preferred_theme_id),
    CONSTRAINT fk_user_preferred_theme_user
        FOREIGN KEY (user_id)
            REFERENCES users (user_id)
            ON DELETE CASCADE,
    CONSTRAINT fk_user_preferred_theme_theme
        FOREIGN KEY (preferred_theme_id)
            REFERENCES preferred_theme (preferred_theme_id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_user_preferred_theme_theme_id
    ON user_preferred_theme (preferred_theme_id);

-- ------------------------------------------------------------
-- 5. plan
-- ------------------------------------------------------------
CREATE TABLE plan (
    plan_id              UUID         NOT NULL,
    plan_name            VARCHAR(100) NOT NULL,
    adult_count          INT          NOT NULL,
    child_count          INT          NOT NULL,
    transportation_type  VARCHAR(20)  NOT NULL,
    is_shared            BOOLEAN      NOT NULL DEFAULT TRUE,
    user_id              UUID         NOT NULL,
    destination_id       INT          NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at           TIMESTAMP,

    CONSTRAINT pk_plan                 PRIMARY KEY (plan_id),
    CONSTRAINT chk_plan_adult_count    CHECK (adult_count >= 0),
    CONSTRAINT chk_plan_child_count    CHECK (child_count >= 0),
    CONSTRAINT chk_plan_transportation CHECK (transportation_type IN ('대중교통', '자가용')),
    CONSTRAINT fk_plan_user
        FOREIGN KEY (user_id)
            REFERENCES users (user_id)
            ON DELETE CASCADE,
    CONSTRAINT fk_plan_destination
        FOREIGN KEY (destination_id)
            REFERENCES destination (destination_id)
            ON DELETE RESTRICT
);

CREATE INDEX idx_plan_user_id
    ON plan (user_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_plan_destination_id
    ON plan (destination_id);

-- ------------------------------------------------------------
-- 6. plan_member  (plan_editor 대체, OWNER 포함)
-- ------------------------------------------------------------
CREATE TABLE plan_member (
    plan_member_id  BIGINT      GENERATED ALWAYS AS IDENTITY,
    plan_id         UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    role            VARCHAR(10) NOT NULL,
    invited_by      UUID,
    joined_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_plan_member            PRIMARY KEY (plan_member_id),
    CONSTRAINT chk_plan_member_role      CHECK (role IN ('OWNER', 'EDITOR')),
    CONSTRAINT uk_plan_member_user_plan  UNIQUE (user_id, plan_id),
    CONSTRAINT fk_plan_member_plan
        FOREIGN KEY (plan_id)
            REFERENCES plan (plan_id)
            ON DELETE CASCADE,
    CONSTRAINT fk_plan_member_user
        FOREIGN KEY (user_id)
            REFERENCES users (user_id)
            ON DELETE CASCADE,
    CONSTRAINT fk_plan_member_invited_by
        FOREIGN KEY (invited_by)
            REFERENCES users (user_id)
            ON DELETE SET NULL
);

CREATE INDEX idx_plan_member_plan_id
    ON plan_member (plan_id);

-- ------------------------------------------------------------
-- 7. time_table
-- ------------------------------------------------------------
CREATE TABLE time_table (
    time_table_id         BIGINT    GENERATED ALWAYS AS IDENTITY,
    date                  DATE      NOT NULL,
    time_table_start_time TIME      NOT NULL,
    time_table_end_time   TIME      NOT NULL,
    plan_id               UUID      NOT NULL,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_time_table           PRIMARY KEY (time_table_id),
    CONSTRAINT uk_time_table_plan_date UNIQUE (plan_id, date),
    CONSTRAINT fk_time_table_plan
        FOREIGN KEY (plan_id)
            REFERENCES plan (plan_id)
            ON DELETE CASCADE
);

-- ------------------------------------------------------------
-- 8. time_table_place_block
-- ------------------------------------------------------------
CREATE TABLE time_table_place_block (
    block_id         BIGINT       GENERATED ALWAYS AS IDENTITY,
    time_table_id    BIGINT       NOT NULL,
    place_id         VARCHAR(100),
    place_name       VARCHAR(255) NOT NULL,
    place_rating     NUMERIC(3, 1),
    place_address    TEXT,
    latitude         NUMERIC(10, 8),
    longitude        NUMERIC(11, 8),
    block_start_time TIME         NOT NULL,
    block_end_time   TIME         NOT NULL,
    block_category   VARCHAR(10)  NOT NULL,
    memo             TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_time_table_place_block
        PRIMARY KEY (block_id),
    CONSTRAINT uk_time_table_place_block_time
        UNIQUE (time_table_id, block_start_time),
    CONSTRAINT chk_block_category
        CHECK (block_category IN ('관광지', '숙소', '식당', '자유', '검색')),
    CONSTRAINT chk_block_place_rating
        CHECK (place_rating IS NULL OR place_rating BETWEEN 0.0 AND 5.0),
    CONSTRAINT fk_block_time_table
        FOREIGN KEY (time_table_id)
            REFERENCES time_table (time_table_id)
            ON DELETE CASCADE
);

-- ------------------------------------------------------------
-- 9. collaboration_request
-- ------------------------------------------------------------
CREATE TABLE collaboration_request (
    collaboration_request_id BIGINT      GENERATED ALWAYS AS IDENTITY,
    type                     VARCHAR(10) NOT NULL,
    status                   VARCHAR(10) NOT NULL,
    sender_id                UUID        NOT NULL,
    receiver_id              UUID        NOT NULL,
    plan_id                  UUID        NOT NULL,
    created_at               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_collaboration_request PRIMARY KEY (collaboration_request_id),
    CONSTRAINT chk_collab_req_type      CHECK (type   IN ('INVITE', 'REQUEST')),
    CONSTRAINT chk_collab_req_status    CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT fk_collab_req_sender
        FOREIGN KEY (sender_id)
            REFERENCES users (user_id)
            ON DELETE CASCADE,
    CONSTRAINT fk_collab_req_receiver
        FOREIGN KEY (receiver_id)
            REFERENCES users (user_id)
            ON DELETE CASCADE,
    CONSTRAINT fk_collab_req_plan
        FOREIGN KEY (plan_id)
            REFERENCES plan (plan_id)
            ON DELETE CASCADE
);

-- PENDING 상태 중 동일 조합 중복 방지
CREATE UNIQUE INDEX uk_collaboration_request_pending
    ON collaboration_request (sender_id, receiver_id, plan_id, type)
    WHERE status = 'PENDING';

CREATE INDEX idx_collab_req_receiver_status
    ON collaboration_request (receiver_id, status);

CREATE INDEX idx_collab_req_sender_status
    ON collaboration_request (sender_id, status);

CREATE INDEX idx_collab_req_plan_id
    ON collaboration_request (plan_id);

-- ------------------------------------------------------------
-- 10. beta_feedback  (변경 없음)
-- ------------------------------------------------------------
CREATE TABLE beta_feedback (
    feedback_id BIGINT    GENERATED ALWAYS AS IDENTITY,
    content     TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_beta_feedback PRIMARY KEY (feedback_id)
);
