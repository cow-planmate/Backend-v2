package com.planmate.common.externalAPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@Slf4j
public class GooglePlacePhotoClient {
    private static final String DETAILS_URL = "https://maps.googleapis.com/maps/api/place/details/json";
    private static final String PHOTO_URL = "https://maps.googleapis.com/maps/api/place/photo";
    private static final int MAX_PHOTO_DIMENSION = 400;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String googleApiKey;

    public GooglePlacePhotoClient(@Qualifier("googlePlacesRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper, @Value("${api.google.key:}") String googleApiKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.googleApiKey = googleApiKey;
    }

    public ResponseEntity<byte[]> fetch(String placeId) {
        String reference = fetchPhotoReference(placeId);
        return reference == null ? null : fetchPhotoBytes(reference);
    }

    /** Text Search 응답에 photo_reference가 없을 때만 호출 — Place Details API로 추가 조회. */
    private String fetchPhotoReference(String placeId) {
        try {
            String url = DETAILS_URL
                    + "?place_id=" + URLEncoder.encode(placeId, StandardCharsets.UTF_8)
                    + "&fields=photos"
                    + "&key=" + URLEncoder.encode(googleApiKey, StandardCharsets.UTF_8);
            String body = restTemplate.getForObject(URI.create(url), String.class);
            if (body == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            if (!"OK".equals(root.path("status").asText())) {
                return null;
            }
            JsonNode photos = root.path("result").path("photos");
            if (photos.isArray() && !photos.isEmpty()) {
                String reference = photos.get(0).path("photo_reference").asText(null);
                return (reference == null || reference.isBlank()) ? null : reference;
            }
        } catch (Exception e) {
            log.warn("Google Place Details 조회 실패 (placeId={}): {}", placeId, e.getMessage());
        }
        return null;
    }

    private ResponseEntity<byte[]> fetchPhotoBytes(String photoReference) {
        try {
            String url = PHOTO_URL
                    + "?maxwidth=" + MAX_PHOTO_DIMENSION
                    + "&photoreference=" + URLEncoder.encode(photoReference, StandardCharsets.UTF_8)
                    + "&key=" + URLEncoder.encode(googleApiKey, StandardCharsets.UTF_8);
            ResponseEntity<byte[]> response = restTemplate.getForEntity(URI.create(url), byte[].class);
            return response;
        } catch (Exception e) {
            log.warn("Google Place Photo 다운로드 실패: {}", e.getMessage());
            return null;
        }
    }

}
