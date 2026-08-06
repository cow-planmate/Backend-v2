-- ------------------------------------------------------------
-- plan 체크리스트
-- plan_checklist_item: plan 멤버 전체가 공유하는 체크리스트.
-- plan_personal_checklist_item: 사용자별 개인 체크리스트.
-- time_table과 동일하게 소프트 삭제 없이 하드 삭제로 관리한다.
-- ------------------------------------------------------------
CREATE TABLE plan_checklist_item (
    checklist_item_id BIGINT       GENERATED ALWAYS AS IDENTITY,
    plan_id           UUID         NOT NULL,
    content           VARCHAR(255) NOT NULL,
    is_checked        BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_plan_checklist_item PRIMARY KEY (checklist_item_id),
    CONSTRAINT chk_plan_checklist_item_sort_order CHECK (sort_order >= 0),
    CONSTRAINT fk_plan_checklist_item_plan
        FOREIGN KEY (plan_id)
            REFERENCES plan (plan_id)
            ON DELETE CASCADE
);

CREATE INDEX idx_plan_checklist_item_plan_id
    ON plan_checklist_item (plan_id);

CREATE TABLE plan_personal_checklist_item (
    personal_checklist_item_id BIGINT       GENERATED ALWAYS AS IDENTITY,
    plan_id                    UUID         NOT NULL,
    user_id                    UUID         NOT NULL,
    content                    VARCHAR(255) NOT NULL,
    is_checked                 BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order                 INT          NOT NULL DEFAULT 0,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_plan_personal_checklist_item PRIMARY KEY (personal_checklist_item_id),
    CONSTRAINT chk_plan_personal_checklist_item_sort_order CHECK (sort_order >= 0),
    CONSTRAINT fk_plan_personal_checklist_item_plan
        FOREIGN KEY (plan_id)
            REFERENCES plan (plan_id)
            ON DELETE CASCADE,
    CONSTRAINT fk_plan_personal_checklist_item_user
        FOREIGN KEY (user_id)
            REFERENCES users (user_id)
            ON DELETE CASCADE
);

CREATE INDEX idx_plan_personal_checklist_item_plan_user
    ON plan_personal_checklist_item (plan_id, user_id);
