package com.planmate.domain.destination.controller;

import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.service.DestinationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/destination")
@RequiredArgsConstructor
public class DestinationController {

    private final DestinationService destinationService;

    @GetMapping
    public ResponseEntity<DestinationListResponse> getDestinations() {
        return ResponseEntity.ok(destinationService.getDestinations());
    }
}
