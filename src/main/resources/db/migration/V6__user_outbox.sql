-- ------------------------------------------------------------
-- 사용자 변경 outbox
--
-- Community가 자기 DB에 사용자 읽기 모델을 복제하기 위한 변경 로그다.
-- 행은 사용자 변경과 같은 트랜잭션에서 기록되므로(UserOutboxWriter는
-- propagation=MANDATORY) "DB는 바뀌었는데 알림은 안 갔다"가 구조적으로 불가능하다.
--
-- seq는 커밋 순서와 일치한다 — UserOutboxWriter가 INSERT 직전에
-- pg_advisory_xact_lock을 잡기 때문이다. 이 락이 없으면 값 자체는 nextval 시점에
-- 정해지는데 가시성은 커밋 시점이라, 늦게 커밋된 낮은 seq를 소비자가 영영 건너뛴다
-- (WHERE seq > 커서로 읽으므로 한 번 지나간 값은 다시 보이지 않는다).
-- 롤백은 seq에 영구 구멍을 남기지만 소비자가 특정 값을 기다리지 않아 무해하다.
--
-- 페이로드는 델타가 아니라 매번 전체 스냅샷이다. 그래야 재생 순서가 뒤바뀌거나
-- 중간 행이 정리(pruning)돼도 최종 상태가 같다.
-- ------------------------------------------------------------
CREATE TABLE user_outbox (
    seq               BIGSERIAL    NOT NULL,
    user_id           UUID         NOT NULL,

    -- 탈퇴 계정은 닉네임·이미지·아바타를 NULL로 넣는다.
    -- InternalUserResponse.ofDeleted 및 proto InternalUser.deleted 주석의 규칙과 같다 —
    -- 탈퇴 후에도 개인정보가 커뮤니티로 계속 흘러갈 이유가 없다.
    nickname          VARCHAR(100),
    profile_image_url VARCHAR(500),

    -- 이메일 MD5(GravatarHash). 이메일을 바꾸는 경로가 코드에 없어 사용자 수명 내내
    -- 불변이므로, 기록 시점에 한 번 계산해 두면 낡지 않는다. User.email 주석 참고.
    avatar_hash       VARCHAR(32),

    profile_public    BOOLEAN      NOT NULL,
    deleted           BOOLEAN      NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_outbox PRIMARY KEY (seq)
);

-- UserOutboxPruner의 보존 기간 정리용.
-- 보존 기간(기본 14일)은 소비자가 따라잡을 수 있는 최대 정지 시간인 동시에,
-- 탈퇴자의 옛 닉네임이 이 테이블에 남아 있는 최대 기간이기도 하다.
CREATE INDEX idx_user_outbox_created_at ON user_outbox (created_at);
