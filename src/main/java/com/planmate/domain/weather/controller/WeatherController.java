package com.planmate.domain.weather.controller;

import com.planmate.domain.weather.dto.WeatherRequest;
import com.planmate.domain.weather.dto.WeatherResponse;
import com.planmate.domain.weather.service.WeatherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    @GetMapping
    public ResponseEntity<WeatherResponse> getWeather(@Valid @ModelAttribute WeatherRequest request) {
        return ResponseEntity.ok(weatherService.getWeather(request));
    }
}
