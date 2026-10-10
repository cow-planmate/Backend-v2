package com.planmate.domain.destination.service;

import com.planmate.domain.destination.cache.CachedResponse;
import com.planmate.domain.destination.cache.DestinationResponseCache;
import com.planmate.domain.destination.dto.DestinationDto;
import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.dto.DestinationNodeDto;
import com.planmate.domain.destination.dto.DestinationTreeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class DestinationServiceTest {

    @Mock
    private DestinationResponseCache destinationResponseCache;

    @InjectMocks
    private DestinationService destinationService;

    // ---------- getDestinations ----------

    @Test
    @DisplayName("여행지 1차 목록 조회 성공 - 기동 시 만들어 둔 featured 목록과 ETag를 그대로 반환한다")
    void getDestinations_success() {
        CachedResponse<DestinationListResponse> cached = new CachedResponse<>(
                DestinationListResponse.builder()
                        .destinations(List.of(new DestinationDto(1, "서울"), new DestinationDto(20, "부산")))
                        .build(),
                "etag-list");
        given(destinationResponseCache.featuredList()).willReturn(cached);

        CachedResponse<DestinationListResponse> result = destinationService.getDestinations();

        assertThat(result.etag()).isEqualTo("etag-list");
        assertThat(result.body().destinations())
                .extracting(DestinationDto::destinationName)
                .containsExactly("서울", "부산");
    }

    // ---------- getDestinationTree ----------

    @Test
    @DisplayName("여행지 트리 조회 성공 - 기동 시 만들어 둔 트리와 ETag를 그대로 반환한다")
    void getDestinationTree_success() {
        DestinationNodeDto gyeonggi = DestinationNodeDto.builder()
                .destinationId(29).destinationName("경기").featured(false)
                .children(List.of(DestinationNodeDto.builder()
                        .destinationId(3).destinationName("수원").featured(true).build()))
                .build();
        CachedResponse<DestinationTreeResponse> cached = new CachedResponse<>(
                DestinationTreeResponse.builder().sidos(List.of(gyeonggi)).build(),
                "etag-tree");
        given(destinationResponseCache.tree()).willReturn(cached);

        CachedResponse<DestinationTreeResponse> result = destinationService.getDestinationTree();

        assertThat(result.etag()).isEqualTo("etag-tree");
        assertThat(result.body().sidos()).containsExactly(gyeonggi);
    }
}
