-- ------------------------------------------------------------
-- Gender.OTHER("기타") 제거
-- 기존에 '기타'로 저장된 로우는 매핑할 대체값이 없어 NULL로 되돌린다
-- (gender 컬럼은 nullable) — CHECK 제약을 좁히기 전에 선행되어야 위반이 없다.
-- ------------------------------------------------------------
UPDATE users SET gender = NULL WHERE gender = '기타';

ALTER TABLE users
    DROP CONSTRAINT chk_users_gender,
    ADD CONSTRAINT chk_users_gender CHECK (gender IN ('남성', '여성'));
