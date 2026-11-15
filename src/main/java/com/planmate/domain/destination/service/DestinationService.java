package com.planmate.domain.destination.service;

import com.planmate.domain.destination.cache.CachedResponse;
import com.planmate.domain.destination.cache.DestinationResponseCache;
import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.dto.DestinationTreeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 여행지 목록·트리 조회. 응답은 기동 시 {@link DestinationResponseCache}가 미리 만들어 두므로
 * 요청 시 DB 접근이 없다 → {@code @Transactional}을 붙이지 않는다(기능_구현_표준 7장).
 */
@Service
@RequiredArgsConstructor
public class DestinationService {

    private final DestinationResponseCache destinationResponseCache;

    /** 1차 기본 목록 — featured 노드만, (부모 순서, 자기 순서) 정렬. B_계층_상세.md §4-6·§5. */
    public CachedResponse<DestinationListResponse> getDestinations() {
        return destinationResponseCache.featuredList();
    }

    /** 상세 선택용 전체 트리(시도 → 시군). B_계층_상세.md §5-1. */
    public CachedResponse<DestinationTreeResponse> getDestinationTree() {
        return destinationResponseCache.tree();
    }
}
