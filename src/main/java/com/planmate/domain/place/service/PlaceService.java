package com.planmate.domain.place.service;

import com.planmate.common.exception.domain.DestinationNotFoundException;
import com.planmate.domain.destination.cache.DestinationRegionCodeCache;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.place.dto.PlaceSearchCriteria;
import com.planmate.domain.place.dto.PlaceSearchRequest;
import com.planmate.domain.place.dto.PlaceSearchResponse;
import com.planmate.domain.place.dto.PlaceSummaryDto;
import com.planmate.domain.place.repository.PlaceCacheRepository;
import com.planmate.domain.place.enums.PlaceCategory;
import com.planmate.domain.theme.cache.PreferredThemeLclsSystmCache;
import com.planmate.domain.theme.entity.PreferredThemeLclsSystmCode;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목적지·카테고리 기준 추천 장소 목록 조회.
 * 데이터 출처: TourAPI 실시간 호출 → tourdata 캐시 DB(project_ai/04_기능/ai/03_추천장소_캐시전환_설계.md).
 */
@Service
@RequiredArgsConstructor
public class PlaceService {

    private final DestinationRegionCodeCache destinationRegionCodeCache;
    private final UserPreferredThemeRepository userPreferredThemeRepository;
    private final PreferredThemeLclsSystmCache preferredThemeLclsSystmCache;
    private final PlaceCacheRepository placeCacheRepository;
    private final DestinationRepository destinationRepository;

    @Transactional(readOnly = true)
    /**
     * 목적지·카테고리 기준 추천 장소를 페이지 단위로 조회한다.
     *
     * <p>현행 대비: 지역코드×선호테마 조합만큼 TourAPI를 병렬 호출해 라운드로빈 병합하던 것을,
     * tourdata 캐시 {@code place_list} 단일 SQL로 대체한다. 다중 소스 병합·재계산형 페이지네이션이
     * 사라지고 {@code totalCount}가 정확해진다(테마 유저는 스코프가 "테마 매칭"→"지역+카테고리 전체"로 바뀜).
     * project_ai/04_기능/ai/03_추천장소_캐시전환_설계.md.
     */
    public PlaceSearchResponse searchPlaces(PlaceSearchRequest request, UUID userId) {
        List<DestinationRegionCode> regionCodes = destinationRegionCodeCache.get(request.destinationId());
        if (regionCodes.isEmpty()) {
            throw new DestinationNotFoundException();
        }

        List<PreferredThemeLclsSystmCode> matchedThemes = resolveMatchedThemes(userId, request.category());
        PlaceSearchCriteria criteria = toCriteria(request, regionCodes, matchedThemes);

        List<PlaceSummaryDto> places = placeCacheRepository.search(criteria).stream()
                .map(PlaceSummaryDto::from)
                .toList();
        long totalCount = placeCacheRepository.count(criteria);
        boolean hasNext = criteria.offset() + places.size() < totalCount;

        return PlaceSearchResponse.builder()
                .places(places)
                .totalCount(totalCount)
                .page(request.page())
                .size(request.size())
                .hasNext(hasNext)
                .build();
    }

    // 요청 + 인메모리 캐시(지역코드/선호테마)를 캐시 조회용 파라미터 객체로 변환.
    // category.contentTypeIds(): ATTRACTION=[12,14](관광지+문화시설, API 콤마 미지원 우회책이던 7:3 배분 제거),
    // ACCOMMODATION=[32], RESTAURANT=[39] — SQL IN 절로 한 번에 조회.
    private PlaceSearchCriteria toCriteria(PlaceSearchRequest request, List<DestinationRegionCode> regionCodes,
            List<PreferredThemeLclsSystmCode> matchedThemes) {
        List<PlaceSearchCriteria.RegionCode> regions = regionCodes.stream()
                .map(rc -> new PlaceSearchCriteria.RegionCode(rc.getLdongRegnCd(), rc.getLdongSignguCd()))
                .toList();
        List<PlaceSearchCriteria.ThemeCode> themes = matchedThemes.stream()
                .map(tc -> new PlaceSearchCriteria.ThemeCode(tc.getLclsSystmLevel(), tc.getLclsSystmCd()))
                .toList();
        return new PlaceSearchCriteria(request.destinationId(), request.category().contentTypeIds(),
                regions, themes, request.page(), request.size());
    }

    // 요청 카테고리(ATTRACTION/ACCOMMODATION/RESTAURANT)를 선호테마 카테고리로 변환해, 로그인 사용자의
    // 해당 카테고리 선호테마만 골라 lclsSystm(레벨+코드) 매핑으로 평탄화한다. 매핑 없는 테마는 조용히 제외.
    // 비로그인이면 빈 리스트 → 캐시 조회에서 테마 랭킹 없이 순수 셔플.
    private List<PreferredThemeLclsSystmCode> resolveMatchedThemes(UUID userId, PlaceCategory category) {
        if (userId == null) {
            return List.of();
        }
        PreferredThemeCategory themeCategory = toPreferredThemeCategory(category);
        return userPreferredThemeRepository.findByUserUserId(userId).stream()
                .filter(upt -> upt.getPreferredTheme().getCategory() == themeCategory)
                .flatMap(upt -> preferredThemeLclsSystmCache.get(upt.getPreferredTheme().getPreferredThemeId()).stream())
                .toList();
    }

    private PreferredThemeCategory toPreferredThemeCategory(PlaceCategory category) {
        return switch (category) {
            case ATTRACTION -> PreferredThemeCategory.ATTRACTION;
            case ACCOMMODATION -> PreferredThemeCategory.ACCOMMODATION;
            case RESTAURANT -> PreferredThemeCategory.RESTAURANT;
        };
    }
}
