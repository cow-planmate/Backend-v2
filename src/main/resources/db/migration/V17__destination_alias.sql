-- ============================================================
-- V17: 여행지 별칭(destination_alias)
--   설계: planmate-ai/project_ai/22_지역_참조데이터_동기화_설계.md §5-1
--
--   - 챗봇이 채팅 문장의 지역명("경상북도", "전라남도")을 여행지 노드로 해석할 때 쓴다.
--     예전에는 AI 저장소(regions.py)에 따로 있던 이름표를 원본(v2)으로 옮긴 것이다 — 사본이
--     따로 있어서 행정구역 개편(전남·광주 → 12 통합)을 놓쳤다.
--   - 넣는 것: 접미사 제거 규칙으로 만들 수 없는 이름 + 정확 일치로 고정해야 하는 정식명 + 영문.
--     "서울특별시"·"파주시"처럼 접미사만 떼면 노드 이름이 되는 것은 AI 정규화가 처리하므로 넣지 않는다.
--   - 같은 별칭이 여러 노드에 걸리는 것은 허용한다("고성" → 강원 고성·경남 고성). 겹침은 AI 가
--     플랜 목적지 기준으로 좁히고, 그래도 안 되면 사용자에게 되묻는다.
--   - 대상 노드는 지역코드로만 찾는다(V16 과 같은 원칙): 시도 노드는 (시도코드, NULL),
--     시군 노드는 자기 대표 매핑 코드. 광주는 대표코드가 없어 동구(210)로 찾는다.
-- ============================================================

CREATE TABLE destination_alias (
    destination_alias_id INT          GENERATED ALWAYS AS IDENTITY,
    destination_id       INT          NOT NULL,
    alias                VARCHAR(100) NOT NULL,

    CONSTRAINT pk_destination_alias PRIMARY KEY (destination_alias_id),
    CONSTRAINT uk_destination_alias UNIQUE (destination_id, alias),
    CONSTRAINT fk_destination_alias_destination
        FOREIGN KEY (destination_id) REFERENCES destination (destination_id)
            ON DELETE CASCADE
);

INSERT INTO destination_alias (destination_id, alias)
SELECT rc.destination_id, v.alias
FROM (VALUES
        -- 시도 노드 (시도코드, NULL)
        ('11',    NULL,  'seoul'),
        ('26',    NULL,  'busan'),
        ('27',    NULL,  'daegu'),
        ('28',    NULL,  'incheon'),
        ('30',    NULL,  'daejeon'),
        ('31',    NULL,  'ulsan'),
        ('36110', NULL,  'sejong'),
        ('41',    NULL,  '경기도'),
        ('41',    NULL,  'gyeonggi'),
        ('51',    NULL,  '강원도'),
        ('51',    NULL,  'gangwon'),
        ('43',    NULL,  '충청북도'),
        ('43',    NULL,  'chungbuk'),
        ('44',    NULL,  '충청남도'),
        ('44',    NULL,  'chungnam'),
        ('52',    NULL,  '전라북도'),
        ('52',    NULL,  'jeonbuk'),
        ('12',    NULL,  '전라남도'),
        ('12',    NULL,  '전남'),
        ('12',    NULL,  '전남광주통합특별시'),  -- 접미사 "특별시"를 떼도 노드 이름(전남광주)이 안 된다
        ('12',    NULL,  'jeonnam'),
        ('47',    NULL,  '경상북도'),
        ('47',    NULL,  'gyeongbuk'),
        ('48',    NULL,  '경상남도'),
        ('48',    NULL,  'gyeongnam'),
        ('50',    NULL,  '제주특별자치도'),  -- 정규화하면 "제주"(제주시 노드)와 겹쳐, 원형 정확 일치로 고정
        ('50',    NULL,  '제주'),
        ('50',    NULL,  'jeju'),
        -- 시군 노드 (대표 매핑 코드)
        ('12',    '210', '광주광역시'),  -- 정규화하면 "광주"(경기 광주 별칭)와 겹쳐, 원형 정확 일치로 고정
        ('12',    '210', 'gwangju'),
        ('41',    '610', '광주'),        -- 경기 광주. 이름 겹침으로 시도 접두가 붙은 노드
        ('51',    '820', '고성'),        -- 강원 고성
        ('48',    '820', '고성')         -- 경남 고성
) AS v (ldong_regn_cd, ldong_signgu_cd, alias)
JOIN destination_region_code rc
    ON rc.ldong_regn_cd = v.ldong_regn_cd
   AND rc.ldong_signgu_cd IS NOT DISTINCT FROM v.ldong_signgu_cd;

-- 위 VALUES 33행이 각각 노드 하나에 정확히 붙었는지 확인한다. 코드가 어느 노드에도 없으면 행이
-- 빠지고(< 33), 한 코드가 두 노드에 매핑돼 있으면 행이 불어난다(> 33) — 어느 쪽이든 조용히
-- 넘어가면 챗봇이 그 이름을 못 찾거나 엉뚱한 노드로 해석하므로 마이그레이션을 실패시킨다.
DO $$
DECLARE
    alias_count INT;
BEGIN
    SELECT count(*) INTO alias_count FROM destination_alias;
    IF alias_count <> 33 THEN
        RAISE EXCEPTION 'destination_alias 시드가 33행이어야 하는데 %행이다 — 지역코드 매핑을 확인할 것', alias_count;
    END IF;
END $$;
