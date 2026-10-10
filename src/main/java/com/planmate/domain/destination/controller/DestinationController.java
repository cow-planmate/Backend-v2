package com.planmate.domain.destination.controller;

import com.planmate.domain.destination.cache.CachedResponse;
import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.dto.DestinationTreeResponse;
import com.planmate.domain.destination.service.DestinationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequestMapping("/api/destination")
@RequiredArgsConstructor
public class DestinationController {

    private final DestinationService destinationService;

    @GetMapping
    public ResponseEntity<DestinationListResponse> getDestinations(WebRequest webRequest) {
        return conditional(destinationService.getDestinations(), webRequest);
    }

    @GetMapping("/tree")
    public ResponseEntity<DestinationTreeResponse> getDestinationTree(WebRequest webRequest) {
        return conditional(destinationService.getDestinationTree(), webRequest);
    }

    // 클라이언트가 가진 ETag(If-None-Match)가 같으면 304(본문 없음), 아니면 200 + ETag.
    // If-None-Match는 약한 비교라 Cloudflare가 W/를 붙여 돌려보내도 일치한다(B_계층_상세.md §5-2).
    private static <T> ResponseEntity<T> conditional(CachedResponse<T> cached, WebRequest webRequest) {
        if (webRequest.checkNotModified(cached.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(cached.etag()).build();
        }
        return ResponseEntity.ok().eTag(cached.etag()).body(cached.body());
    }
}
