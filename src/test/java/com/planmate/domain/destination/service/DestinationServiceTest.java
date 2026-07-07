package com.planmate.domain.destination.service;

import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class DestinationServiceTest {

    @Mock
    private DestinationRepository destinationRepository;

    @InjectMocks
    private DestinationService destinationService;

    // ---------- getDestinations ----------

    @Test
    @DisplayName("여행지 목록 조회 성공 - 전체 목록을 destinationId/destinationName만 담아 반환")
    void getDestinations_success() {
        Destination seoul = Destination.builder()
                .destinationId(1)
                .destinationName("서울")
                .latitude(new BigDecimal("37.56330000"))
                .longitude(new BigDecimal("126.97970000"))
                .build();
        Destination busan = Destination.builder()
                .destinationId(2)
                .destinationName("부산")
                .latitude(new BigDecimal("35.17960000"))
                .longitude(new BigDecimal("129.07560000"))
                .build();
        given(destinationRepository.findAll()).willReturn(List.of(seoul, busan));

        DestinationListResponse response = destinationService.getDestinations();

        assertThat(response.destinations()).hasSize(2);
        assertThat(response.destinations().get(0).destinationId()).isEqualTo(1);
        assertThat(response.destinations().get(0).destinationName()).isEqualTo("서울");
        assertThat(response.destinations().get(1).destinationName()).isEqualTo("부산");
    }

    @Test
    @DisplayName("여행지 목록 조회 성공 - 데이터가 없으면 빈 리스트 반환")
    void getDestinations_success_empty() {
        given(destinationRepository.findAll()).willReturn(List.of());

        DestinationListResponse response = destinationService.getDestinations();

        assertThat(response.destinations()).isEmpty();
    }
}
