package com.planmate.domain.image.controller;

import com.planmate.domain.image.service.PlaceImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PlaceImageController {
    private final PlaceImageService imageService;

    @GetMapping({"/api/image/place/{placeId}", "/image/place/{placeId}"})
    public ResponseEntity<byte[]> image(@PathVariable String placeId) {
        return imageService.getImage(placeId);
    }
}
