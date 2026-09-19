package com.planmate.domain.place.controller;

import com.planmate.domain.place.dto.PlaceDetailResponse;
import com.planmate.domain.place.dto.PlaceSearchRequest;
import com.planmate.domain.place.dto.PlaceSearchResponse;
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

}
