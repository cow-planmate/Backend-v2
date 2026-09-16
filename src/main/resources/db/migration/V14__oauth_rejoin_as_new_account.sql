-- ============================================================
-- OAuth 재가입 정책 변경: 탈퇴 계정 자동 복구 → 로컬과 동일하게 신규 계정 생성
-- provider_id 재사용을 막던 전체범위 유니크 인덱스를, 이메일/닉네임과 같은
-- "활성 행만 유일" 부분 유니크 인덱스로 완화한다. 기존 제약을 느슨하게 바꾸는
-- 방향이라 기존 데이터와 충돌할 여지가 없다.
-- ============================================================

DROP INDEX uk_users_provider_provider_id;

CREATE UNIQUE INDEX uk_users_provider_provider_id
    ON users (provider, provider_id)
    WHERE deleted_at IS NULL;
