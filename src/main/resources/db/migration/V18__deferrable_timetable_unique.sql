-- ============================================================
-- V18: 일정 날짜·블록 시각 유니크 제약을 DEFERRABLE INITIALLY IMMEDIATE 로
--   설계: SharedSync/project_ai/06_묶음E_저장순서_설계.md §4-3
--
--   - 편집은 SharedSync 캐시에 먼저 쌓이고, 동기화가 그 최종 상태를 DB로 옮긴다. 행을 쓰는 순서는 캐시
--     순회 순서로 정해져서, 최종 상태가 정상이어도 옮기는 도중에 같은 키가 두 줄이 되면 실패했다.
--     예: 여행 시작일 변경(날짜 전체를 하루씩 이동), 블록을 옮기고 빈 시각에 새 블록, 블록 시각 맞바꾸기.
--     실패하면 그 플랜의 편집 전체가 DB에 들어가지 않고 다음 동기화도 계속 실패했다.
--   - DEFERRABLE 로 선언해 두면 SharedSync 가 동기화 트랜잭션 안에서만 이 두 제약을 커밋 때 검사하게
--     바꾼다(SET CONSTRAINTS ... DEFERRED). 최종 상태가 규칙을 어기면 지금처럼 실패한다.
--   - INITIALLY IMMEDIATE 라 동기화 밖의 쓰기(플랜 생성 REST 등)는 지금처럼 문장마다 검사한다.
--   - UNIQUE 의 지연 가능 여부는 ALTER CONSTRAINT 로 바꿀 수 없어(FK 만 가능) 지우고 같은 이름·컬럼으로
--     다시 만든다. 두 제약 모두 ON CONFLICT 판정이나 FK 참조에 쓰이지 않는다(DEFERRABLE 이면 쓸 수 없다).
-- ============================================================

ALTER TABLE time_table
    DROP CONSTRAINT uk_time_table_plan_date,
    ADD CONSTRAINT uk_time_table_plan_date UNIQUE (plan_id, date) DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE time_table_place_block
    DROP CONSTRAINT uk_time_table_place_block_time,
    ADD CONSTRAINT uk_time_table_place_block_time
        UNIQUE (time_table_id, block_start_time) DEFERRABLE INITIALLY IMMEDIATE;
