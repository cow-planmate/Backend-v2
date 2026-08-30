package com.planmate.domain.place.dto;

import java.util.List;

/**
 * 추천 장소 캐시 조회의 내부 파라미터 객체. Service가 요청 + 인메모리 캐시(지역코드/선호테마)를
 * 조합해 만들고 {@code PlaceCacheRepository}에 넘긴다.
 *
 * <p>Repository가 {@code destination}/{@code theme} 도메인 엔티티를 몰라도 되게, SQL이 실제로
 * 쓰는 값만 담는다. project_ai/04_기능/ai/03_추천장소_캐시전환_설계.md §3·§4-1.
 */
public record PlaceSearchCriteria(
        Integer destinationId,
        List<String> contentTypeIds,
        List<RegionCode> regionCodes,
        List<ThemeCode> themeCodes,
        int page,
        int size
) {
    /** 목적지에 매핑된 법정동 지역코드. {@code ldongSignguCd}가 null이면 시/도 전체. */
    public record RegionCode(String ldongRegnCd, String ldongSignguCd) {
    }

    /** 선호테마 → lclsSystm 필터. {@code level} 1/2/3 이 각각 {@code lcls_systm1/2/3} 컬럼에 대응. */
    public record ThemeCode(int level, String code) {
    }

    /** 비정상적으로 큰 page(오버플로 방지 위해 long)여도 음수 OFFSET 이 안 되게. 큰 값이면 그냥 0건. */
    public long offset() {
        return (long) (page - 1) * size;
    }
}
