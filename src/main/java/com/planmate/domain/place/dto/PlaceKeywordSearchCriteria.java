package com.planmate.domain.place.dto;

/**
 * 장소 이름 검색 캐시 조회의 내부 파라미터 객체. {@code PlaceSearchCriteria}(추천 장소)와 달리
 * 지역/카테고리/테마 필터가 없다 — 이름을 아는 특정 장소를 전국 어디든 바로 찾는 용도라
 * 스코프를 좁히지 않는다. place/10_장소검색_캐시DB_설계.md §5·§7-1.
 */
public record PlaceKeywordSearchCriteria(String query, int page, int size) {

    public long offset() {
        return (long) (page - 1) * size;
    }
}
