package com.planmate.domain.place.dto;

import com.planmate.common.externalAPI.KakaoLocalClient.KakaoPlace;
import java.util.List;

/**
 * 키워드 장소 검색 결과.
 *
 * 카카오 응답을 그대로 흘려보내지 않는 이유: 응답 스키마가 바뀌면 프론트가 같이 깨지고,
 * x/y가 (경도, 위도) 순서라 그대로 넘기면 호출부마다 헷갈린다. 여기서 lat/lng로 이름을 붙인다.
 */
public record PlaceKeywordSearchResponse(List<Place> places) {

    public record Place(
            String id,
            /** 장소명 (예: 카페 델문도) */
            String name,
            /** 도로명 주소, 없으면 지번 주소 */
            String address,
            /** 지번 주소 — 도로명이 없는 옛 건물이 있어 함께 내려준다 */
            String jibunAddress,
            String phone,
            /** "음식점 > 카페 > 커피전문점" 형태 */
            String category,
            /** 카카오맵 상세 페이지 */
            String url,
            Double lat,
            Double lng
    ) {}

    public static PlaceKeywordSearchResponse from(List<KakaoPlace> places) {
        return new PlaceKeywordSearchResponse(places.stream()
                .map(p -> new Place(p.id(), p.name(), p.address(), p.jibunAddress(),
                        p.phone(), p.category(), p.url(), p.lat(), p.lng()))
                .toList());
    }
}
