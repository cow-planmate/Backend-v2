package com.planmate.domain.place.service;

import com.planmate.common.exception.domain.DestinationNotFoundException;
import com.planmate.common.exception.domain.TourApiCallFailedException;
import com.planmate.common.externalAPI.TourApiClient;
import com.planmate.common.externalAPI.TourApiClient.AreaBasedListItem;
import com.planmate.common.externalAPI.TourApiClient.AreaBasedListResult;
import com.planmate.domain.destination.cache.DestinationRegionCodeCache;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.place.dto.PlaceSearchRequest;
import com.planmate.domain.place.dto.PlaceSearchResponse;
import com.planmate.domain.place.enums.PlaceCategory;
import com.planmate.domain.theme.cache.PreferredThemeLclsSystmCache;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.PreferredThemeLclsSystmCode;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private TourApiClient tourApiClient;

    private ExecutorService executorService;
    private PlaceService placeService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(4);
        placeService = new PlaceService(destinationRegionCodeCache, userPreferredThemeRepository,
                preferredThemeLclsSystmCache, tourApiClient, executorService);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    private DestinationRegionCode regionCode(String ldongRegnCd, String ldongSignguCd) throws Exception {
        DestinationRegionCode regionCode = DestinationRegionCode.builder()
                .ldongRegnCd(ldongRegnCd)
                .ldongSignguCd(ldongSignguCd)
                .build();
        // destination 연관관계는 destinationId 접근에만 쓰이므로 캐시 그룹핑 검증엔 불필요 - 테스트에선 세팅 생략
        return regionCode;
    }

    private AreaBasedListItem item(String contentId, String contentTypeId) {
        return new AreaBasedListItem(contentId, contentTypeId, "장소" + contentId, "주소", null,
                127.0, 37.0, "https://img/" + contentId, "Type1", "11");
    }

    private PlaceSearchRequest request(PlaceCategory category, int page, int size) {
        return new PlaceSearchRequest(DESTINATION_ID, category, page, size);
    }

    @Test
    @DisplayName("searchPlaces 성공 - 비로그인 사용자, 단일 지역, 선호테마 필터 없음")
    void searchPlaces_success_noLogin_singleRegion() throws Exception {
        given(destinationRegionCodeCache.get(DESTINATION_ID))
                .willReturn(List.of(regionCode("11", null)));
        given(tourApiClient.getAreaBasedList(eq("11"), isNull(), eq("32"), isNull(), isNull(), eq(20), eq(1)))
                .willReturn(new AreaBasedListResult(List.of(item("1", "32"), item("2", "32")), 50));

        PlaceSearchResponse response = placeService.searchPlaces(request(PlaceCategory.ACCOMMODATION, 1, 20), null);

        assertThat(response.places()).hasSize(2);
        assertThat(response.totalCount()).isEqualTo(50);
        assertThat(response.hasNext()).isTrue();
        verify(tourApiClient, times(1)).getAreaBasedList(any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(userPreferredThemeRepository, never()).findByUserUserId(any());
    }

    @Test
    @DisplayName("searchPlaces 성공 - 로그인 사용자, 선호테마가 레벨/코드로 정확히 매핑되어 호출됨")
    void searchPlaces_appliesPreferredThemeLclsSystmFilter() throws Exception {
        PreferredTheme koreanFood = PreferredTheme.builder()
                .preferredThemeId(5)
                .preferredThemeName("한식")
                .category(PreferredThemeCategory.RESTAURANT)
                .build();
        UserPreferredTheme userTheme = UserPreferredTheme.builder()
                .preferredTheme(koreanFood)
                .build();

        given(destinationRegionCodeCache.get(DESTINATION_ID))
                .willReturn(List.of(regionCode("11", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of(userTheme));
        given(preferredThemeLclsSystmCache.get(5))
                .willReturn(List.of(PreferredThemeLclsSystmCode.builder()
                        .preferredTheme(koreanFood)
                        .lclsSystmLevel(2)
                        .lclsSystmCd("FD01")
                        .build()));
        List<AreaBasedListItem> fullPage = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> item("koreanFood-" + i, "39"))
                .toList();
        given(tourApiClient.getAreaBasedList(eq("11"), isNull(), eq("39"), eq(2), eq("FD01"), eq(20), eq(1)))
                .willReturn(new AreaBasedListResult(fullPage, 30));

        PlaceSearchResponse response = placeService.searchPlaces(request(PlaceCategory.RESTAURANT, 1, 20), USER_ID);

        assertThat(response.places()).hasSize(20);
        verify(tourApiClient).getAreaBasedList(eq("11"), isNull(), eq("39"), eq(2), eq("FD01"), eq(20), eq(1));
    }

    @Test
    @DisplayName("searchPlaces - 선호테마가 다른 카테고리 소속이면 필터에 반영되지 않음")
    void searchPlaces_ignoresPreferredThemeOfDifferentCategory() throws Exception {
        PreferredTheme attractionTheme = PreferredTheme.builder()
                .preferredThemeId(7)
                .preferredThemeName("해수욕장")
                .category(PreferredThemeCategory.ATTRACTION)
                .build();
        UserPreferredTheme userTheme = UserPreferredTheme.builder()
                .preferredTheme(attractionTheme)
                .build();

        given(destinationRegionCodeCache.get(DESTINATION_ID))
                .willReturn(List.of(regionCode("11", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of(userTheme));
        given(tourApiClient.getAreaBasedList(eq("11"), isNull(), eq("39"), isNull(), isNull(), eq(20), eq(1)))
                .willReturn(new AreaBasedListResult(List.of(item("20", "39")), 10));

        placeService.searchPlaces(request(PlaceCategory.RESTAURANT, 1, 20), USER_ID);

        verify(tourApiClient).getAreaBasedList(eq("11"), isNull(), eq("39"), isNull(), isNull(), eq(20), eq(1));
        verify(preferredThemeLclsSystmCache, never()).get(any());
    }

    @Test
    @DisplayName("searchPlaces - ATTRACTION 카테고리는 관광지(12)/문화시설(14)을 7:3 비율로 나눠 호출")
    void searchPlaces_tourCategorySplits7to3() throws Exception {
        given(destinationRegionCodeCache.get(DESTINATION_ID))
                .willReturn(List.of(regionCode("11", null)));
        given(tourApiClient.getAreaBasedList(eq("11"), isNull(), eq("12"), isNull(), isNull(), eq(14), eq(1)))
                .willReturn(new AreaBasedListResult(List.of(item("1", "12"), item("2", "12")), 100));
        given(tourApiClient.getAreaBasedList(eq("11"), isNull(), eq("14"), isNull(), isNull(), eq(6), eq(1)))
                .willReturn(new AreaBasedListResult(List.of(item("3", "14")), 10));

        PlaceSearchResponse response = placeService.searchPlaces(request(PlaceCategory.ATTRACTION, 1, 20), null);

        assertThat(response.places()).hasSize(3);
        verify(tourApiClient).getAreaBasedList(eq("11"), isNull(), eq("12"), isNull(), isNull(), eq(14), eq(1));
        verify(tourApiClient).getAreaBasedList(eq("11"), isNull(), eq("14"), isNull(), isNull(), eq(6), eq(1));
    }

    @Test
    @DisplayName("searchPlaces - destination_region_code 매핑이 없으면 DestinationNotFoundException")
    void searchPlaces_destinationNotFound() {
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of());

        assertThatThrownBy(() -> placeService.searchPlaces(request(PlaceCategory.ATTRACTION, 1, 20), null))
                .isInstanceOf(DestinationNotFoundException.class);
    }

    @Test
    @DisplayName("searchPlaces - 단일 소스 TourAPI 호출 실패는 그대로 전파됨")
    void searchPlaces_singleSourceFailurePropagates() throws Exception {
        given(destinationRegionCodeCache.get(DESTINATION_ID))
                .willReturn(List.of(regionCode("11", null)));
        given(tourApiClient.getAreaBasedList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .willThrow(new TourApiCallFailedException());

        assertThatThrownBy(() -> placeService.searchPlaces(request(PlaceCategory.ACCOMMODATION, 1, 20), null))
                .isInstanceOf(TourApiCallFailedException.class);
    }

    @Test
    @DisplayName("searchPlaces - 지역코드가 여러 개면 병렬 호출 후 contentId 기준 중복 제거")
    void searchPlaces_multiRegionMergeDedupe() throws Exception {
        given(destinationRegionCodeCache.get(DESTINATION_ID))
                .willReturn(List.of(regionCode("11", "111"), regionCode("11", "112")));
        given(tourApiClient.getAreaBasedList(eq("11"), eq("111"), eq("32"), isNull(), isNull(), anyInt(), eq(1)))
                .willReturn(new AreaBasedListResult(List.of(item("100", "32"), item("101", "32")), 2));
        given(tourApiClient.getAreaBasedList(eq("11"), eq("112"), eq("32"), isNull(), isNull(), anyInt(), eq(1)))
                .willReturn(new AreaBasedListResult(List.of(item("100", "32"), item("102", "32")), 2));

        PlaceSearchResponse response = placeService.searchPlaces(request(PlaceCategory.ACCOMMODATION, 1, 20), null);

        assertThat(response.places())
                .extracting(place -> place.contentId())
                .containsExactlyInAnyOrder("100", "101", "102");
    }

    @Test
    @DisplayName("searchPlaces - 선호테마 필터 결과가 부족하면 일반 목록으로 채움")
    void searchPlaces_fillsShortfallWithBaselineWhenThemeFilterIsThin() throws Exception {
        PreferredTheme koreanFood = PreferredTheme.builder()
                .preferredThemeId(5)
                .preferredThemeName("한식")
                .category(PreferredThemeCategory.RESTAURANT)
                .build();
        UserPreferredTheme userTheme = UserPreferredTheme.builder()
                .preferredTheme(koreanFood)
                .build();

        given(destinationRegionCodeCache.get(DESTINATION_ID))
                .willReturn(List.of(regionCode("11", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of(userTheme));
        given(preferredThemeLclsSystmCache.get(5))
                .willReturn(List.of(PreferredThemeLclsSystmCode.builder()
                        .preferredTheme(koreanFood)
                        .lclsSystmLevel(2)
                        .lclsSystmCd("FD01")
                        .build()));
        given(tourApiClient.getAreaBasedList(eq("11"), isNull(), eq("39"), eq(2), eq("FD01"), eq(20), eq(1)))
                .willReturn(new AreaBasedListResult(List.of(item("1", "39")), 1));
        given(tourApiClient.getAreaBasedList(eq("11"), isNull(), eq("39"), isNull(), isNull(), eq(20), eq(1)))
                .willReturn(new AreaBasedListResult(List.of(item("1", "39"), item("2", "39"), item("3", "39")), 30));

        PlaceSearchResponse response = placeService.searchPlaces(request(PlaceCategory.RESTAURANT, 1, 20), USER_ID);

        assertThat(response.places())
                .extracting(place -> place.contentId())
                .containsExactlyInAnyOrder("1", "2", "3");
        verify(tourApiClient).getAreaBasedList(eq("11"), isNull(), eq("39"), eq(2), eq("FD01"), eq(20), eq(1));
        verify(tourApiClient).getAreaBasedList(eq("11"), isNull(), eq("39"), isNull(), isNull(), eq(20), eq(1));
    }
}
