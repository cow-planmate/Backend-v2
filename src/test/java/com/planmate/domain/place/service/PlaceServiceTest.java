package com.planmate.domain.place.service;

import com.planmate.common.exception.domain.DestinationNotFoundException;
import com.planmate.common.exception.domain.GooglePlacesApiCallFailedException;
import com.planmate.common.exception.domain.PlaceCacheQueryException;
import com.planmate.common.externalAPI.GooglePlaceImageWorker;
import com.planmate.common.externalAPI.GoogleSearchClient;
import com.planmate.common.externalAPI.GoogleSearchClient.GoogleTextSearchItem;
import com.planmate.common.externalAPI.GoogleSearchClient.GoogleTextSearchResult;
import com.planmate.common.externalAPI.KakaoLocalClient;
import com.planmate.common.storage.GooglePlaceImageStorage;
import com.planmate.domain.destination.cache.DestinationRegionCodeCache;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.place.dto.PlaceSearchCriteria;
import com.planmate.domain.place.dto.PlaceSearchRequest;
import com.planmate.domain.place.dto.PlaceSearchResponse;
import com.planmate.domain.place.dto.PlaceTextSearchNextRequest;
import com.planmate.domain.place.dto.PlaceTextSearchRequest;
import com.planmate.domain.place.dto.PlaceTextSearchResponse;
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
    private KakaoLocalClient kakaoLocalClient;
    @Mock
    private DestinationRepository destinationRepository;
    @Mock
    private GoogleSearchClient googleSearchClient;
    @Mock
    private GooglePlaceImageStorage googlePlaceImageStorage;
    @Mock
    private GooglePlaceImageWorker googlePlaceImageWorker;

    private PlaceService placeService;

    @BeforeEach
    void setUp() {
        placeService = new PlaceService(destinationRegionCodeCache, userPreferredThemeRepository,
                preferredThemeLclsSystmCache, placeCacheRepository, kakaoLocalClient,
                destinationRepository, googleSearchClient, googlePlaceImageStorage, googlePlaceImageWorker);
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

    // ---------- searchByText (Google Text Search, 07_키워드검색_API_상세설계.md) — 변경 없음 ----------

    private GoogleTextSearchItem googleItem(String placeId, String photoReference) {
        return new GoogleTextSearchItem(placeId, "장소" + placeId, "주소", 37.5, 127.0, photoReference);
    }

    @Test
    @DisplayName("searchByText - destinationId 없으면 좌표 바이어스 없이 순수 텍스트로 검색")
    void searchByText_noDestinationId_noBias() {
        given(googleSearchClient.searchPlace("경복궁", null, null))
                .willReturn(new GoogleTextSearchResult(List.of(googleItem("place-1", null)), null));
        given(googlePlaceImageStorage.findExistingUrl("place-1")).willReturn(null);

        PlaceTextSearchResponse response = placeService.searchByText(new PlaceTextSearchRequest("경복궁", null));

        assertThat(response.places()).hasSize(1);
        assertThat(response.places().get(0).placeId()).isEqualTo("place-1");
        assertThat(response.nextPageToken()).isNull();
        verify(destinationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("searchByText - destinationId가 있으면 그 목적지 좌표를 Google에 바이어스로 전달")
    void searchByText_withDestinationId_passesCoordinatesAsBias() {
        Destination busan = Destination.builder()
                .destinationId(DESTINATION_ID)
                .destinationName("부산")
                .latitude(new BigDecimal("35.17945000"))
                .longitude(new BigDecimal("129.07556000"))
                .build();
        given(destinationRepository.findById(DESTINATION_ID)).willReturn(Optional.of(busan));
        given(googleSearchClient.searchPlace(eq("해운대"), eq(35.17945), eq(129.07556)))
                .willReturn(new GoogleTextSearchResult(List.of(googleItem("place-2", null)), "next-token"));
        given(googlePlaceImageStorage.findExistingUrl("place-2")).willReturn(null);

        PlaceTextSearchResponse response = placeService.searchByText(new PlaceTextSearchRequest("해운대", DESTINATION_ID));

        assertThat(response.places()).hasSize(1);
        assertThat(response.nextPageToken()).isEqualTo("next-token");
        verify(googleSearchClient).searchPlace("해운대", 35.17945, 129.07556);
    }

    @Test
    @DisplayName("searchByText - 존재하지 않는 destinationId면 DestinationNotFoundException")
    void searchByText_destinationNotFound() {
        given(destinationRepository.findById(999)).willReturn(Optional.empty());

        assertThatThrownBy(() -> placeService.searchByText(new PlaceTextSearchRequest("아무거나", 999)))
                .isInstanceOf(DestinationNotFoundException.class);
    }

    @Test
    @DisplayName("searchByText - MinIO에 이미 사진이 있으면 그 URL을 쓰고 Google 재요청은 안 함")
    void searchByText_usesCachedThumbnail_doesNotRefetch() {
        given(googleSearchClient.searchPlace("스타벅스", null, null))
                .willReturn(new GoogleTextSearchResult(List.of(googleItem("place-3", "ref-3")), null));
        given(googlePlaceImageStorage.findExistingUrl("place-3"))
                .willReturn("http://minio/planmate/googleplace/place-3.webp");

        PlaceTextSearchResponse response = placeService.searchByText(new PlaceTextSearchRequest("스타벅스", null));

        assertThat(response.places().get(0).thumbnailUrl())
                .isEqualTo("http://minio/planmate/googleplace/place-3.webp");
        verify(googlePlaceImageWorker, never()).fetchAndUploadAsync(any(), any());
    }

    @Test
    @DisplayName("searchByText - MinIO에 사진이 없으면 thumbnailUrl은 null로 즉시 응답하고 비동기 채움만 트리거")
    void searchByText_missingThumbnail_triggersAsyncFillWithoutBlocking() {
        given(googleSearchClient.searchPlace("무명카페", null, null))
                .willReturn(new GoogleTextSearchResult(List.of(googleItem("place-4", "ref-4")), null));
        given(googlePlaceImageStorage.findExistingUrl("place-4")).willReturn(null);

        PlaceTextSearchResponse response = placeService.searchByText(new PlaceTextSearchRequest("무명카페", null));

        assertThat(response.places().get(0).thumbnailUrl()).isNull();
        verify(googlePlaceImageWorker, times(1)).fetchAndUploadAsync("place-4", "ref-4");
    }

    @Test
    @DisplayName("searchByText - 평점 등 Google 전용 필드는 응답에 없다(추천과 동일 필드만 노출)")
    void searchByText_responseHasNoRatingLikeFields() {
        given(googleSearchClient.searchPlace("아무데나", null, null))
                .willReturn(new GoogleTextSearchResult(List.of(googleItem("place-5", null)), null));
        given(googlePlaceImageStorage.findExistingUrl("place-5")).willReturn(null);

        PlaceTextSearchResponse response = placeService.searchByText(new PlaceTextSearchRequest("아무데나", null));

        assertThat(response.places().get(0).name()).isEqualTo("장소place-5");
        assertThat(response.places().get(0).latitude()).isEqualTo(37.5);
        assertThat(response.places().get(0).longitude()).isEqualTo(127.0);
    }

    @Test
    @DisplayName("searchByText - Google API 호출 실패는 그대로 전파됨")
    void searchByText_googleApiFailurePropagates() {
        given(googleSearchClient.searchPlace(any(), any(), any()))
                .willThrow(new GooglePlacesApiCallFailedException());

        assertThatThrownBy(() -> placeService.searchByText(new PlaceTextSearchRequest("아무거나", null)))
                .isInstanceOf(GooglePlacesApiCallFailedException.class);
    }

    @Test
    @DisplayName("searchByTextNextPage - 직전 응답의 pageToken을 그대로 Google에 전달하고 동일하게 응답을 구성")
    void searchByTextNextPage_passesTokenThrough() {
        given(googleSearchClient.searchNextPage("token-abc"))
                .willReturn(new GoogleTextSearchResult(List.of(googleItem("place-6", null)), "token-def"));
        given(googlePlaceImageStorage.findExistingUrl("place-6")).willReturn(null);

        PlaceTextSearchResponse response = placeService.searchByTextNextPage(
                new PlaceTextSearchNextRequest("검색어", "token-abc"));

        assertThat(response.places()).hasSize(1);
        assertThat(response.nextPageToken()).isEqualTo("token-def");
        verify(googleSearchClient).searchNextPage("token-abc");
    }
}
