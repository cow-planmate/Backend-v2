package com.planmate.domain.place.service;

import com.planmate.common.exception.domain.DestinationNotFoundException;
import com.planmate.common.exception.domain.TourApiCallFailedException;
import com.planmate.common.exception.domain.KakaoLocalNotConfiguredException;
import com.planmate.common.externalAPI.GooglePlaceImageWorker;
import com.planmate.common.externalAPI.GoogleSearchClient;
import com.planmate.common.externalAPI.GoogleSearchClient.GoogleTextSearchItem;
import com.planmate.common.externalAPI.GoogleSearchClient.GoogleTextSearchResult;
import com.planmate.common.externalAPI.KakaoLocalClient;
import com.planmate.common.externalAPI.TourApiClient;
import com.planmate.common.externalAPI.TourApiClient.AreaBasedListItem;
import com.planmate.common.externalAPI.TourApiClient.AreaBasedListResult;
import com.planmate.common.storage.GooglePlaceImageStorage;
import com.planmate.domain.destination.cache.DestinationRegionCodeCache;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.place.dto.PlaceKeywordSearchResponse;
import com.planmate.domain.place.dto.PlaceSearchRequest;
import com.planmate.domain.place.dto.PlaceSearchResponse;
import com.planmate.domain.place.dto.PlaceSummaryDto;
import com.planmate.domain.place.dto.PlaceTextSearchNextRequest;
import com.planmate.domain.place.dto.PlaceTextSearchRequest;
import com.planmate.domain.place.dto.PlaceTextSearchResponse;
import com.planmate.domain.place.dto.PlaceTextSearchResultDto;
import com.planmate.domain.place.enums.PlaceCategory;
import com.planmate.domain.theme.cache.PreferredThemeLclsSystmCache;
import com.planmate.domain.theme.entity.PreferredThemeLclsSystmCode;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목적지·카테고리 기준 추천 장소 목록 조회.
 * project_ai/04_기능/place/05_추천장소_API_상세설계.md 3장.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlaceService {

    // 다중 소스 병합 시 소스 1개당 요청하는 numOfRows 상한(잠정값) — page가 비정상적으로 커도 호출량이 폭주하지 않게 막는 안전장치.
    private static final int MAX_NUM_OF_ROWS_PER_SOURCE = 100;
    // 지역코드×선호테마 조합 병렬 호출 전체 타임아웃(잠정값). 04_추천장소_API_인프라설계.md 3-4절.
    private static final long PARALLEL_CALL_TIMEOUT_SECONDS = 8;

    private final DestinationRegionCodeCache destinationRegionCodeCache;
    private final UserPreferredThemeRepository userPreferredThemeRepository;
    private final PreferredThemeLclsSystmCache preferredThemeLclsSystmCache;
    private final TourApiClient tourApiClient;
    private final KakaoLocalClient kakaoLocalClient;
    private final ExecutorService tourApiExecutor;
    private final DestinationRepository destinationRepository;
    private final GoogleSearchClient googleSearchClient;
    private final GooglePlaceImageStorage googlePlaceImageStorage;
    private final GooglePlaceImageWorker googlePlaceImageWorker;

    @Transactional(readOnly = true)
    /**
     * 키워드로 장소를 찾는다 (카카오 로컬).
     *
     * TourAPI 기반 {@link #searchPlaces}가 "목적지+카테고리로 둘러보기"라면, 이쪽은
     * 사용자가 이름을 아는 특정 장소를 집는 용도다. 커뮤니티 장소 추천 글쓰기가 쓴다.
     */
    public PlaceKeywordSearchResponse searchByKeyword(String query, Integer size) {
        if (query == null || query.isBlank()) {
            return new PlaceKeywordSearchResponse(List.of());
        }
        if (!kakaoLocalClient.isConfigured()) {
            throw new KakaoLocalNotConfiguredException();
        }
        int limit = size == null ? 10 : Math.min(Math.max(size, 1), 15);
        return PlaceKeywordSearchResponse.from(kakaoLocalClient.searchKeyword(query.trim(), limit));
    }

    /**
     * Google Places Text Search 기반 자유 텍스트 장소 검색. 카카오 로컬({@link #searchByKeyword})과는
     * 팀원이 별도로 만든 용도라 이름/경로 모두 분리돼 있다({@code /api/place/text-search}).
     * project_ai/04_기능/place/07_키워드검색_API_상세설계.md §3.
     */
    @Transactional(readOnly = true)
    public PlaceTextSearchResponse searchByText(PlaceTextSearchRequest request) {
        Double[] bias = resolveBiasCoordinates(request.destinationId());
        GoogleTextSearchResult result = googleSearchClient.searchPlace(request.query(), bias[0], bias[1]);
        return buildTextSearchResponse(result);
    }

    /** 직전 응답의 nextPageToken을 그대로 왕복시켜 다음 페이지를 조회한다. 서버 상태 없음. */
    @Transactional(readOnly = true)
    public PlaceTextSearchResponse searchByTextNextPage(PlaceTextSearchNextRequest request) {
        GoogleTextSearchResult result = googleSearchClient.searchNextPage(request.pageToken());
        return buildTextSearchResponse(result);
    }

    // destinationId가 있으면 그 목적지 좌표로 소프트 가중치, 없으면 [null, null](순수 텍스트 검색).
    // 06_키워드검색_API_인프라설계.md §3-2 — Geocoding API 재호출 없이 DB에 이미 저장된 좌표를 그대로 읽는다.
    private Double[] resolveBiasCoordinates(Integer destinationId) {
        if (destinationId == null) {
            return new Double[] {null, null};
        }
        Destination destination = destinationRepository.findById(destinationId)
                .orElseThrow(DestinationNotFoundException::new);
        return new Double[] {
                destination.getLatitude().doubleValue(),
                destination.getLongitude().doubleValue()
        };
    }

    private PlaceTextSearchResponse buildTextSearchResponse(GoogleTextSearchResult result) {
        List<PlaceTextSearchResultDto> places = new ArrayList<>();
        for (GoogleTextSearchItem item : result.items()) {
            String thumbnailUrl = googlePlaceImageStorage.findExistingUrl(item.placeId());
            if (thumbnailUrl == null) {
                // 응답은 이 처리를 기다리지 않는다 — 실패해도 검색 자체는 정상 반환(fire-and-forget).
                googlePlaceImageWorker.fetchAndUploadAsync(item.placeId(), item.photoReference());
            }
            places.add(PlaceTextSearchResultDto.from(item, thumbnailUrl));
        }
        return PlaceTextSearchResponse.builder()
                .places(places)
                .nextPageToken(result.nextPageToken())
                .build();
    }

    public PlaceSearchResponse searchPlaces(PlaceSearchRequest request, UUID userId) {
        List<DestinationRegionCode> regionCodes = destinationRegionCodeCache.get(request.destinationId());
        if (regionCodes.isEmpty()) {
            throw new DestinationNotFoundException();
        }

        List<PreferredThemeLclsSystmCode> matchedThemes = resolveMatchedThemes(userId, request.category());

        if (request.category() == PlaceCategory.ATTRACTION) {
            return searchTour(regionCodes, matchedThemes, request);
        }

        String contentTypeId = request.category().contentTypeIds().get(0);
        ContentTypeResult result = fetchForContentType(regionCodes, contentTypeId, matchedThemes,
                request.size(), request.page());
        return buildResponse(result.items(), result.totalCountApprox(), result.hasNext(), request);
    }

    // ATTRACTION = 관광지(12)+문화시설(14) 고정 비율 7:3 배분, 한쪽이 소진되면 반대쪽에서 채움 (3장 3단계).
    private PlaceSearchResponse searchTour(List<DestinationRegionCode> regionCodes,
            List<PreferredThemeLclsSystmCode> matchedThemes, PlaceSearchRequest request) {

        String attractionTypeId = PlaceCategory.ATTRACTION.contentTypeIds().get(0);
        String cultureTypeId = PlaceCategory.ATTRACTION.contentTypeIds().get(1);

        int[] split = tourSizeSplit(request.size());
        int attractionSize = split[0];
        int cultureSize = split[1];

        ContentTypeResult attraction = fetchForContentType(regionCodes, attractionTypeId, matchedThemes, attractionSize, request.page());
        ContentTypeResult culture = fetchForContentType(regionCodes, cultureTypeId, matchedThemes, cultureSize, request.page());

        List<AreaBasedListItem> combined = mergeDistinct(attraction.items(), culture.items(), request.size());

        int shortfall = request.size() - combined.size();
        if (shortfall > 0) {
            if (attraction.items().size() >= attractionSize && culture.items().size() < cultureSize) {
                ContentTypeResult moreAttraction = fetchForContentType(regionCodes, attractionTypeId, matchedThemes,
                        attractionSize + shortfall, request.page());
                combined = mergeDistinct(moreAttraction.items(), culture.items(), request.size());
            } else if (culture.items().size() >= cultureSize && attraction.items().size() < attractionSize) {
                ContentTypeResult moreCulture = fetchForContentType(regionCodes, cultureTypeId, matchedThemes,
                        cultureSize + shortfall, request.page());
                combined = mergeDistinct(attraction.items(), moreCulture.items(), request.size());
            }
        }

        long totalCountApprox = attraction.totalCountApprox() + culture.totalCountApprox();
        boolean hasNext = attraction.hasNext() || culture.hasNext();
        return buildResponse(combined, totalCountApprox, hasNext, request);
    }

    private int[] tourSizeSplit(int size) {
        int attractionSize = (int) Math.round(size * 0.7);
        int cultureSize = size - attractionSize;
        return new int[] {attractionSize, cultureSize};
    }

    // 선호테마 필터 결과가 부족하면 테마 필터 없이 한 번 더 채운다 (3장 6단계, v1 계승).
    private ContentTypeResult fetchForContentType(List<DestinationRegionCode> regionCodes, String contentTypeId,
            List<PreferredThemeLclsSystmCode> themeCodes, int allocatedSize, int page) {

        if (allocatedSize <= 0) {
            return new ContentTypeResult(List.of(), 0, false);
        }

        List<SourceCall> calls = buildSourceCalls(regionCodes, contentTypeId, themeCodes);
        MergeOutcome outcome = fetchAndMerge(calls, allocatedSize, page);

        if (!themeCodes.isEmpty() && outcome.items().size() < allocatedSize) {
            List<SourceCall> baseCalls = buildSourceCalls(regionCodes, contentTypeId, List.of());
            MergeOutcome baseOutcome = fetchAndMerge(baseCalls, allocatedSize, page);
            List<AreaBasedListItem> filled = mergeDistinct(outcome.items(), baseOutcome.items(), allocatedSize);
            outcome = new MergeOutcome(filled, Math.max(outcome.totalCountApprox(), baseOutcome.totalCountApprox()),
                    outcome.hasNext() || baseOutcome.hasNext());
        }

        return new ContentTypeResult(outcome.items(), outcome.totalCountApprox(), outcome.hasNext());
    }

    private List<SourceCall> buildSourceCalls(List<DestinationRegionCode> regionCodes, String contentTypeId,
            List<PreferredThemeLclsSystmCode> themeCodes) {
        List<SourceCall> calls = new ArrayList<>();
        for (DestinationRegionCode regionCode : regionCodes) {
            if (themeCodes.isEmpty()) {
                calls.add(new SourceCall(regionCode, contentTypeId, null));
            } else {
                for (PreferredThemeLclsSystmCode themeCode : themeCodes) {
                    calls.add(new SourceCall(regionCode, contentTypeId, themeCode));
                }
            }
        }
        return calls;
    }

    // 단일 소스면 TourAPI의 pageNo를 그대로 쓰고, 다중 소스면 재계산형(3-1절)으로 page*size(+1)만큼 매번 다시 병합한다.
    private MergeOutcome fetchAndMerge(List<SourceCall> calls, int wantedSize, int page) {
        if (calls.isEmpty()) {
            return new MergeOutcome(List.of(), 0, false);
        }

        if (calls.size() == 1) {
            SourceCall only = calls.get(0);
            AreaBasedListResult result = callSource(only, wantedSize, page);
            boolean hasNext = (long) page * wantedSize < result.totalCount();
            return new MergeOutcome(result.items(), result.totalCount(), hasNext);
        }

        int neededForPage = page * wantedSize;
        int probeCount = neededForPage + 1;
        int perSourceRows = Math.min(MAX_NUM_OF_ROWS_PER_SOURCE, probeCount);

        List<AreaBasedListResult> results = callSourcesInParallel(calls, perSourceRows, 1);
        long totalCountApprox = results.stream().mapToLong(AreaBasedListResult::totalCount).sum();
        List<AreaBasedListItem> merged = roundRobinMerge(results, probeCount);

        boolean hasNext = merged.size() > neededForPage;
        int fromIndex = Math.min((page - 1) * wantedSize, merged.size());
        int toIndex = Math.min(neededForPage, merged.size());
        List<AreaBasedListItem> pageItems = merged.subList(fromIndex, toIndex);

        return new MergeOutcome(pageItems, totalCountApprox, hasNext);
    }

    private AreaBasedListResult callSource(SourceCall call, int numOfRows, int pageNo) {
        return tourApiClient.getAreaBasedList(
                call.regionCode().getLdongRegnCd(),
                call.regionCode().getLdongSignguCd(),
                call.contentTypeId(),
                call.themeCode() != null ? call.themeCode().getLclsSystmLevel() : null,
                call.themeCode() != null ? call.themeCode().getLclsSystmCd() : null,
                numOfRows, pageNo);
    }

    // 병렬 실행 + 요청 전체 타임아웃 + 부분 실패 베스트 에포트(04_추천장소_API_인프라설계.md 3-4절).
    private List<AreaBasedListResult> callSourcesInParallel(List<SourceCall> calls, int numOfRows, int pageNo) {
        List<CompletableFuture<AreaBasedListResult>> futures = calls.stream()
                .map(call -> CompletableFuture.supplyAsync(() -> callSource(call, numOfRows, pageNo), tourApiExecutor)
                        .exceptionally(ex -> {
                            log.warn("TourAPI 소스 호출 실패, 결과에서 제외: {}", ex.getMessage());
                            return null;
                        }))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PARALLEL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("TourAPI 병렬 호출 전체 타임아웃/실패: {}", e.getMessage());
        }

        List<AreaBasedListResult> results = futures.stream()
                .map(future -> future.getNow(null))
                .filter(Objects::nonNull)
                .toList();

        if (results.isEmpty()) {
            throw new TourApiCallFailedException();
        }
        return results;
    }

    // contentid 기준으로 먼저 나온 것만 남기고 중복 제거하며 라운드로빈으로 인터리빙한다(3장 5단계).
    private List<AreaBasedListItem> roundRobinMerge(List<AreaBasedListResult> results, int limit) {
        List<List<AreaBasedListItem>> sources = results.stream().map(AreaBasedListResult::items).toList();
        int[] cursors = new int[sources.size()];

        LinkedHashMap<String, AreaBasedListItem> merged = new LinkedHashMap<>();
        boolean progress = true;
        while (progress && merged.size() < limit) {
            progress = false;
            for (int i = 0; i < sources.size(); i++) {
                if (merged.size() >= limit) {
                    break;
                }
                List<AreaBasedListItem> source = sources.get(i);
                if (cursors[i] < source.size()) {
                    AreaBasedListItem item = source.get(cursors[i]);
                    cursors[i]++;
                    merged.putIfAbsent(item.contentId(), item);
                    progress = true;
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<AreaBasedListItem> mergeDistinct(List<AreaBasedListItem> primary, List<AreaBasedListItem> secondary, int limit) {
        LinkedHashMap<String, AreaBasedListItem> merged = new LinkedHashMap<>();
        for (AreaBasedListItem item : primary) {
            if (merged.size() >= limit) {
                break;
            }
            merged.put(item.contentId(), item);
        }
        for (AreaBasedListItem item : secondary) {
            if (merged.size() >= limit) {
                break;
            }
            merged.putIfAbsent(item.contentId(), item);
        }
        return new ArrayList<>(merged.values());
    }

    // 요청 카테고리(ATTRACTION/ACCOMMODATION/RESTAURANT)를 선호테마 카테고리(ATTRACTION/ACCOMMODATION/RESTAURANT)로 변환(7-1절).
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

    private PlaceSearchResponse buildResponse(List<AreaBasedListItem> items, long totalCountApprox, boolean hasNext,
            PlaceSearchRequest request) {
        List<PlaceSummaryDto> places = items.stream().map(PlaceSummaryDto::from).toList();
        return PlaceSearchResponse.builder()
                .places(places)
                .totalCount(totalCountApprox)
                .page(request.page())
                .size(request.size())
                .hasNext(hasNext)
                .build();
    }

    private record SourceCall(DestinationRegionCode regionCode, String contentTypeId, PreferredThemeLclsSystmCode themeCode) {
    }

    private record MergeOutcome(List<AreaBasedListItem> items, long totalCountApprox, boolean hasNext) {
    }

    private record ContentTypeResult(List<AreaBasedListItem> items, long totalCountApprox, boolean hasNext) {
    }
}
