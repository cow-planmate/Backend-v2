-- ------------------------------------------------------------
-- destination 좌표 NOT NULL화
-- 여행지는 관리자가 큐레이션해서 추가하며, 추가 시 좌표까지 항상 함께
-- 입력하기로 결정 (좌표 없는 destination이 존재할 일이 없음).
-- 기존 28개 행은 이미 좌표가 채워져 있어 즉시 SET NOT NULL 가능.
-- ------------------------------------------------------------
ALTER TABLE destination
    ALTER COLUMN latitude SET NOT NULL,
    ALTER COLUMN longitude SET NOT NULL;
