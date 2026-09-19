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
-- AI DB 는 별도(serving_indexes_ai.sql — HNSW 벡터 인덱스 등). 이 파일엔 v2 ②③④용만.

-- ② 추천장소: content_type_id IN (...) AND ldong_regn_cd = ? [AND ldong_signgu_cd = ?] 필터
CREATE INDEX IF NOT EXISTS ix_place_list_regn_type
    ON place_list (ldong_regn_cd, content_type_id, ldong_signgu_cd);

-- ③ 장소상세: 1:N 상세 테이블은 PK 가 BIGSERIAL 이라 content_id 조인용 인덱스가 없다
CREATE INDEX IF NOT EXISTS ix_place_image_content_id       ON place_image (content_id);
CREATE INDEX IF NOT EXISTS ix_place_info_common_content_id ON place_info_common (content_id);
CREATE INDEX IF NOT EXISTS ix_place_info_room_content_id   ON place_info_room (content_id);
CREATE INDEX IF NOT EXISTS ix_place_info_course_content_id ON place_info_course (content_id);

-- ④ 장소 검색: title 부분일치 + 오타 허용(트리그램). place/10_장소검색_캐시DB_설계.md §7-2.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 한글 완성형 음절을 초성/중성/종성으로 분해한다. 2음절 이하 짧은 지명은 음절 단위 trigram이
-- 거의 안 만들어져 오타 허용력이 약해지므로, 자모 단위로 풀면 짧은 지명도 trigram 후보가 충분히 생긴다.
CREATE OR REPLACE FUNCTION hangul_jamo(input text)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    cho  text[] := ARRAY['ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'];
    jung text[] := ARRAY['ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ','ㅙ','ㅚ','ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ'];
    jong text[] := ARRAY['','ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ','ㄻ','ㄼ','ㄽ','ㄾ','ㄿ','ㅀ','ㅁ','ㅂ','ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'];
    code   integer;
    result text := '';
    ch     text;
BEGIN
    -- NULL 가드: title이 NULL인 행이 하나라도 있으면 CREATE INDEX 시점에 정수 FOR 루프
    -- 상한이 NULL이 되어 인덱스 빌드 전체가 깨질 수 있어 IMMUTABLE 함수 초입에 명시적으로 처리한다.
    IF input IS NULL THEN
        RETURN NULL;
    END IF;

    FOR i IN 1..char_length(input) LOOP
        ch := substr(input, i, 1);
        code := ascii(ch); -- UTF8에서 ascii()는 첫 문자의 유니코드 코드포인트를 반환(PG 문서 기준)
        IF code BETWEEN 44032 AND 55203 THEN -- 가(AC00)~힣(D7A3)
            code := code - 44032;
            result := result || cho[(code / 588) + 1] || jung[((code % 588) / 28) + 1];
            IF (code % 28) != 0 THEN
                result := result || jong[(code % 28) + 1];
            END IF;
        ELSE
            result := result || ch;
        END IF;
    END LOOP;
    RETURN result;
END;
$$;

CREATE INDEX IF NOT EXISTS ix_place_list_title_trgm
    ON place_list USING GIN (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_place_list_title_jamo_trgm
    ON place_list USING GIN (hangul_jamo(title) gin_trgm_ops);
