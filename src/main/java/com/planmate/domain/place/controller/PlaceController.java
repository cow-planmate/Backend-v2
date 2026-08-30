package com.planmate.domain.place.controller;

import com.planmate.domain.place.dto.PlaceDetailResponse;
import com.planmate.domain.place.dto.PlaceKeywordSearchResponse;
import com.planmate.domain.place.dto.PlaceSearchRequest;
import com.planmate.domain.place.dto.PlaceSearchResponse;
import com.planmate.domain.place.dto.PlaceTextSearchNextRequest;
import com.planmate.domain.place.dto.PlaceTextSearchRequest;
import com.planmate.domain.place.dto.PlaceTextSearchResponse;
import com.planmate.domain.place.service.PlaceDetailService;
import com.planmate.domain.place.service.PlaceService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/place")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;
    private final PlaceDetailService placeDetailService;

    @GetMapping
    public ResponseEntity<PlaceSearchResponse> searchPlaces(
            @AuthenticationPrincipal(errorOnInvalidType = false) UUID userId,
            @Valid @ModelAttribute PlaceSearchRequest request) {
        return ResponseEntity.ok(placeService.searchPlaces(request, userId));
    }

    /** 장소 상세 조회 (tourdata 캐시). 인증 불필요 — 개인화 없음. 05_장소상세_API_설계.md. */
    @GetMapping("/{contentId}")
    public ResponseEntity<PlaceDetailResponse> getPlaceDetail(@PathVariable String contentId) {
        return ResponseEntity.ok(placeDetailService.getPlaceDetail(contentId));
    }

    /** 키워드로 장소 찾기 (카카오 로컬) — 이름을 아는 특정 장소를 집을 때 쓴다. */
    @GetMapping("/search")
    public ResponseEntity<PlaceKeywordSearchResponse> searchByKeyword(
            @RequestParam String query,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(placeService.searchByKeyword(query, size));
    }

    /**
     * Google Places 기준 자유 텍스트 장소 검색. destinationId(선택)를 넘기면 그 목적지 근처
     * 결과가 우선 노출된다(제한 아님, 소프트 가중치). 인증 불필요 — 개인화 로직이 없어 로그인
     * 여부와 무관하게 동일하게 동작한다. project_ai/04_기능/place/07_키워드검색_API_상세설계.md §4.
     */
    @GetMapping("/text-search")
    public ResponseEntity<PlaceTextSearchResponse> searchByText(@Valid @ModelAttribute PlaceTextSearchRequest request) {
        return ResponseEntity.ok(placeService.searchByText(request));
    }

    /** 검색 결과의 다음 페이지 — Google next_page_token을 그대로 왕복시킨다(서버 상태 없음). */
    @PostMapping("/text-search/next")
    public ResponseEntity<PlaceTextSearchResponse> searchByTextNextPage(@Valid @RequestBody PlaceTextSearchNextRequest request) {
        return ResponseEntity.ok(placeService.searchByTextNextPage(request));
    }
}
