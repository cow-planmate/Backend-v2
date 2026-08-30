-- tourdata 캐시 DB (v2 서빙용) 인덱스.
--
-- 기본 15-테이블 스키마는 수집용 덤프 그대로(인덱스 없음, 02_캐시_테이블_DDL.md). 서빙 최적화는
-- 이 additive layer 로만 분리 관리한다. project_ai/04_기능/ai/03_추천장소_캐시전환_설계.md §4-9.
--
-- 적용:
--   - 로컬:  docker exec -i planmate-v2-tourdata-postgres psql -U postgres -d tourdata < 이 파일
--   - 운영:  적재 절차 문서의 스텝
--   - 최신화 잡: 매 실행 idempotent 하게 재보장(CREATE INDEX IF NOT EXISTS)
--
-- AI DB 는 별도(serving_indexes_ai.sql — HNSW 벡터 인덱스 등). 이 파일엔 v2 ②③용만.

-- ② 추천장소: content_type_id IN (...) AND ldong_regn_cd = ? [AND ldong_signgu_cd = ?] 필터
CREATE INDEX IF NOT EXISTS ix_place_list_regn_type
    ON place_list (ldong_regn_cd, content_type_id, ldong_signgu_cd);

-- ③ 장소상세: 1:N 상세 테이블은 PK 가 BIGSERIAL 이라 content_id 조인용 인덱스가 없다
CREATE INDEX IF NOT EXISTS ix_place_image_content_id       ON place_image (content_id);
CREATE INDEX IF NOT EXISTS ix_place_info_common_content_id ON place_info_common (content_id);
CREATE INDEX IF NOT EXISTS ix_place_info_room_content_id   ON place_info_room (content_id);
CREATE INDEX IF NOT EXISTS ix_place_info_course_content_id ON place_info_course (content_id);
