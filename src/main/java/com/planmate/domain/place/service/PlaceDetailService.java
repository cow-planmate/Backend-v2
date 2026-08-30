package com.planmate.domain.place.service;

import com.planmate.common.exception.domain.PlaceDetailNotFoundException;
import com.planmate.domain.place.dto.PlaceDetailResponse;
import com.planmate.domain.place.dto.PlaceDetailResponse.AccommodationDetail;
import com.planmate.domain.place.dto.PlaceDetailResponse.AttractionDetail;
import com.planmate.domain.place.dto.PlaceDetailResponse.PlaceImageDto;
import com.planmate.domain.place.dto.PlaceDetailResponse.RestaurantDetail;
import com.planmate.domain.place.enums.PlaceCategory;
import com.planmate.domain.place.repository.PlaceDetailRepository;
import com.planmate.domain.place.repository.PlaceDetailRepository.BaseRow;
import com.planmate.domain.place.support.TourdataText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 장소 상세 단건 조회 — tourdata 캐시(읽기 전용) 앱조인.
 * project_ai/04_기능/ai/05_장소상세_API_설계.md. 검색 3종을 가진 {@code PlaceService}와 분리(§4·§15-4).
 *
 * <p>{@code @Transactional} 안 붙임 — {@code tourdataDataSource}엔 트랜잭션 매니저가 없다(수식어 없는
 * {@code @Transactional}은 앱 DB 매니저에 묶여 엉뚱한 커넥션을 잡는다). 05_ §3, 03_ §4-7.
 */
@Service
@RequiredArgsConstructor
public class PlaceDetailService {

    private final PlaceDetailRepository placeDetailRepository;

    public PlaceDetailResponse getPlaceDetail(String contentId) {
        BaseRow base = placeDetailRepository.fetchBase(contentId);
        if (base == null) {
            throw new PlaceDetailNotFoundException();
        }
        PlaceCategory category = supportedCategoryOrThrow(base.contentTypeId());

        return PlaceDetailResponse.builder()
                .contentId(base.contentId())
                .contentTypeId(base.contentTypeId())
                .category(category)
                .title(base.title())
                .addr1(base.addr1())
                .latitude(base.mapy())
                .longitude(base.mapx())
                .imageUrl(base.firstImage())
                .thumbnailUrl(base.thumbnail())
                .copyrightDivCd(base.copyrightDivCd())
                .overview(TourdataText.stripHtml(base.overview()))
                .homepage(TourdataText.extractUrl(base.homepage()))
                .images(PlaceImageDto.listFrom(placeDetailRepository.fetchImages(contentId)))
                .attraction(category == PlaceCategory.ATTRACTION ? attraction(contentId, base.contentTypeId()) : null)
                .accommodation(category == PlaceCategory.ACCOMMODATION ? accommodation(contentId) : null)
                .restaurant(category == PlaceCategory.RESTAURANT ? restaurant(contentId) : null)
                .build();
    }

    private AttractionDetail attraction(String contentId, String contentTypeId) {
        return AttractionDetail.from(
                placeDetailRepository.fetchAttractionIntro(contentId, contentTypeId),
                placeDetailRepository.fetchInfoItems(contentId));
    }

    private AccommodationDetail accommodation(String contentId) {
        return AccommodationDetail.from(
                placeDetailRepository.fetchLodgingIntro(contentId),
                placeDetailRepository.fetchRooms(contentId));
    }

    private RestaurantDetail restaurant(String contentId) {
        return RestaurantDetail.from(placeDetailRepository.fetchFoodIntro(contentId));
    }

    // place_list 실제 content_type_id 가 상세 API 대상(12/14/32/39)이 아니면 404(축제·코스·레포츠·쇼핑).
    // PlaceCategory.fromContentTypeId 는 미지원 값에 IllegalArgumentException 을 던지므로 감싼다(05_ §6).
    private PlaceCategory supportedCategoryOrThrow(String contentTypeId) {
        try {
            return PlaceCategory.fromContentTypeId(contentTypeId);
        } catch (IllegalArgumentException e) {
            throw new PlaceDetailNotFoundException();
        }
    }
}
