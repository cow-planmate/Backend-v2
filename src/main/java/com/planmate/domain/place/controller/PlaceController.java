package com.planmate.domain.place.controller;

import com.planmate.domain.place.dto.PlaceKeywordSearchResponse;
import com.planmate.domain.place.dto.PlaceSearchRequest;
import com.planmate.domain.place.dto.PlaceSearchResponse;
import com.planmate.domain.place.service.PlaceService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/place")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;

    @GetMapping
    public ResponseEntity<PlaceSearchResponse> searchPlaces(
            @AuthenticationPrincipal(errorOnInvalidType = false) UUID userId,
            @Valid @ModelAttribute PlaceSearchRequest request) {
        return ResponseEntity.ok(placeService.searchPlaces(request, userId));
    }

    /** 키워드로 장소 찾기 (카카오 로컬) — 이름을 아는 특정 장소를 집을 때 쓴다. */
    @GetMapping("/search")
    public ResponseEntity<PlaceKeywordSearchResponse> searchByKeyword(
            @RequestParam String query,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(placeService.searchByKeyword(query, size));
    }
}
