package com.planmate.domain.destination.service;

import com.planmate.domain.destination.dto.DestinationDto;
import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.repository.DestinationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DestinationService {

    private final DestinationRepository destinationRepository;

    @Transactional(readOnly = true)
    public DestinationListResponse getDestinations() {
        return DestinationListResponse.builder()
                .destinations(destinationRepository.findAll().stream()
                        .map(DestinationDto::from)
                        .toList())
                .build();
    }
}
