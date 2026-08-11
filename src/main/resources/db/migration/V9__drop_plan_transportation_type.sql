-- ------------------------------------------------------------
-- plan.transportation_type 제거
-- 저장/표시 용도로만 쓰이고 실제 길찾기 로직에는 전혀 사용되지 않아 제거.
-- ------------------------------------------------------------
ALTER TABLE plan
    DROP CONSTRAINT chk_plan_transportation,
    DROP COLUMN transportation_type;
