package com.planmate.domain.place.service;

import static org.mockito.Mockito.verifyNoInteractions;

import com.planmate.common.exception.domain.DestinationNotFoundException;
import com.planmate.common.exception.domain.PlaceCacheQueryException;
import com.planmate.domain.destination.cache.DestinationRegionCodeCache;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.place.dto.PlaceSearchCriteria;
import com.planmate.domain.place.dto.PlaceSearchRequest;
import com.planmate.domain.place.dto.PlaceSearchResponse;
import com.planmate.domain.place.enums.PlaceCategory;
import com.planmate.domain.place.repository.PlaceCacheRepository;
import com.planmate.domain.place.repository.PlaceCacheRepository.PlaceRow;
import com.planmate.domain.theme.cache.PreferredThemeLclsSystmCache;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.PreferredThemeLclsSystmCode;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlaceServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Integer DESTINATION_ID = 1;

    @Mock
    private DestinationRegionCodeCache destinationRegionCodeCache;
    @Mock
    private UserPreferredThemeRepository userPreferredThemeRepository;
    @Mock
    private PreferredThemeLclsSystmCache preferredThemeLclsSystmCache;
    @Mock
    private PlaceCacheRepository placeCacheRepository;
    @Mock
    private DestinationRepository destinationRepository;

    private PlaceService placeService;

    @BeforeEach
    void setUp() {
        placeService = new PlaceService(destinationRegionCodeCache, userPreferredThemeRepository,
                preferredThemeLclsSystmCache, placeCacheRepository, destinationRepository);
    }

    private DestinationRegionCode regionCode(String ldongRegnCd, String ldongSignguCd) {
        // destination 연관관계는 여기서 안 쓰이므로 생략 — 캐시가 이미 그룹핑해서 준 것으로 간주.
        return DestinationRegionCode.builder()
                .ldongRegnCd(ldongRegnCd)
                .ldongSignguCd(ldongSignguCd)
                .build();
    }

    private PlaceRow placeRow(String contentId, String contentTypeId) {
        return new PlaceRow(contentId, contentTypeId, "장소" + contentId, "주소",
                127.0, 37.0, "https://img/" + contentId, "Type1");
    }

    private PlaceSearchRequest request(PlaceCategory category, int page, int size) {
        return new PlaceSearchRequest(DESTINATION_ID, category, page, size);
    }

    private UserPreferredTheme userTheme(int themeId, String name, PreferredThemeCategory category) {
        return UserPreferredTheme.builder()
                .preferredTheme(PreferredTheme.builder()
                        .preferredThemeId(themeId)
                        .preferredThemeName(name)
                        .category(category)
                        .build())
                .build();
    }

    private PreferredThemeLclsSystmCode lclsCode(int level, String code) {
        return PreferredThemeLclsSystmCode.builder()
                .lclsSystmLevel(level)
                .lclsSystmCd(code)
                .build();
    }

    // ---------- searchPlaces (캐시 DB 조회, 03_추천장소_캐시전환_설계.md) ----------

    @Test
    @DisplayName("searchPlaces 성공 - 비로그인이면 선호테마 조회 없이 빈 테마로 캐시 조회, 응답에 count/hasNext 반영")
    void searchPlaces_success_noLogin() {
        ArgumentCaptor<PlaceSearchCriteria> captor = ArgumentCaptor.forClass(PlaceSearchCriteria.class);
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("11", null)));
        given(placeCacheRepository.search(captor.capture()))
                .willReturn(List.of(placeRow("1", "32"), placeRow("2", "32")));
        given(placeCacheRepository.count(any(PlaceSearchCriteria.class))).willReturn(50L);

        PlaceSearchResponse response = placeService.searchPlaces(request(PlaceCategory.ACCOMMODATION, 1, 20), null);

        assertThat(response.places()).hasSize(2);
        assertThat(response.totalCount()).isEqualTo(50L);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.hasNext()).isTrue(); // offset 0 + 2 < 50

        PlaceSearchCriteria criteria = captor.getValue();
        assertThat(criteria.contentTypeIds()).containsExactly("32");
        assertThat(criteria.themeCodes()).isEmpty();
        assertThat(criteria.regionCodes())
                .extracting(PlaceSearchCriteria.RegionCode::ldongRegnCd)
                .containsExactly("11");
        verify(userPreferredThemeRepository, never()).findByUserUserId(any());
    }

    @Test
    @DisplayName("searchPlaces - ATTRACTION은 관광지(12)+문화시설(14)을 한 IN 절로 조회 (7:3 배분 없음)")
    void searchPlaces_attractionQueriesBoth12And14() {
        ArgumentCaptor<PlaceSearchCriteria> captor = ArgumentCaptor.forClass(PlaceSearchCriteria.class);
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("11", null)));
        given(placeCacheRepository.search(captor.capture())).willReturn(List.of(placeRow("1", "12"), placeRow("2", "14")));
        given(placeCacheRepository.count(any(PlaceSearchCriteria.class))).willReturn(2L);

        placeService.searchPlaces(request(PlaceCategory.ATTRACTION, 1, 20), null);

        assertThat(captor.getValue().contentTypeIds()).containsExactly("12", "14");
    }

    @Test
    @DisplayName("searchPlaces - 로그인 사용자의 선호테마가 (레벨, 코드)로 캐시 조회에 전달됨")
    void searchPlaces_passesPreferredThemeLclsCodesToCriteria() {
        ArgumentCaptor<PlaceSearchCriteria> captor = ArgumentCaptor.forClass(PlaceSearchCriteria.class);
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("11", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID))
                .willReturn(List.of(userTheme(5, "한식", PreferredThemeCategory.RESTAURANT)));
        given(preferredThemeLclsSystmCache.get(5)).willReturn(List.of(lclsCode(2, "FD01")));
        given(placeCacheRepository.search(captor.capture())).willReturn(List.of(placeRow("1", "39")));
        given(placeCacheRepository.count(any(PlaceSearchCriteria.class))).willReturn(30L);

        placeService.searchPlaces(request(PlaceCategory.RESTAURANT, 1, 20), USER_ID);

        assertThat(captor.getValue().themeCodes())
                .extracting(PlaceSearchCriteria.ThemeCode::level, PlaceSearchCriteria.ThemeCode::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "FD01"));
    }

    @Test
    @DisplayName("searchPlaces - 선호테마가 다른 카테고리 소속이면 캐시 조회 테마에서 제외")
    void searchPlaces_ignoresPreferredThemeOfDifferentCategory() {
        ArgumentCaptor<PlaceSearchCriteria> captor = ArgumentCaptor.forClass(PlaceSearchCriteria.class);
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("11", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID))
                .willReturn(List.of(userTheme(7, "해수욕장", PreferredThemeCategory.ATTRACTION)));
        given(placeCacheRepository.search(captor.capture())).willReturn(List.of(placeRow("1", "39")));
        given(placeCacheRepository.count(any(PlaceSearchCriteria.class))).willReturn(10L);

        placeService.searchPlaces(request(PlaceCategory.RESTAURANT, 1, 20), USER_ID);

        assertThat(captor.getValue().themeCodes()).isEmpty();
        verify(preferredThemeLclsSystmCache, never()).get(any());
    }

    @Test
    @DisplayName("searchPlaces - 지역코드가 여러 개면 전부 캐시 조회 criteria에 담김")
    void searchPlaces_passesAllRegionCodesToCriteria() {
        ArgumentCaptor<PlaceSearchCriteria> captor = ArgumentCaptor.forClass(PlaceSearchCriteria.class);
        given(destinationRegionCodeCache.get(DESTINATION_ID))
                .willReturn(List.of(regionCode("11", "111"), regionCode("11", "112")));
        given(placeCacheRepository.search(captor.capture())).willReturn(List.of(placeRow("1", "32")));
        given(placeCacheRepository.count(any(PlaceSearchCriteria.class))).willReturn(1L);

        placeService.searchPlaces(request(PlaceCategory.ACCOMMODATION, 1, 20), null);

        assertThat(captor.getValue().regionCodes())
                .extracting(PlaceSearchCriteria.RegionCode::ldongRegnCd, PlaceSearchCriteria.RegionCode::ldongSignguCd)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("11", "111"),
                        org.assertj.core.groups.Tuple.tuple("11", "112"));
    }

    @Test
    @DisplayName("searchPlaces - 마지막 페이지(offset+건수 >= totalCount)면 hasNext=false")
    void searchPlaces_hasNextFalseOnLastPage() {
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("11", null)));
        given(placeCacheRepository.search(any(PlaceSearchCriteria.class)))
                .willReturn(List.of(placeRow("41", "39"), placeRow("42", "39")));
        given(placeCacheRepository.count(any(PlaceSearchCriteria.class))).willReturn(42L);

        // page 3, size 20 → offset 40, + 2건 = 42 == totalCount → 다음 없음
        PlaceSearchResponse response = placeService.searchPlaces(request(PlaceCategory.RESTAURANT, 3, 20), null);

        assertThat(response.hasNext()).isFalse();
    }

    @Test
    @DisplayName("searchPlaces - destination_region_code 매핑이 없으면 DestinationNotFoundException (캐시 조회 안 함)")
    void searchPlaces_destinationNotFound() {
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of());

        assertThatThrownBy(() -> placeService.searchPlaces(request(PlaceCategory.ATTRACTION, 1, 20), null))
                .isInstanceOf(DestinationNotFoundException.class);
        verify(placeCacheRepository, never()).search(any());
    }

    @Test
    @DisplayName("searchPlaces - 캐시 DB 조회 실패(PlaceCacheQueryException)는 그대로 전파됨")
    void searchPlaces_cacheQueryFailurePropagates() {
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("11", null)));
        given(placeCacheRepository.search(any(PlaceSearchCriteria.class)))
                .willThrow(new PlaceCacheQueryException(new RuntimeException("db down")));

        assertThatThrownBy(() -> placeService.searchPlaces(request(PlaceCategory.ACCOMMODATION, 1, 20), null))
                .isInstanceOf(PlaceCacheQueryException.class);
    }

}
