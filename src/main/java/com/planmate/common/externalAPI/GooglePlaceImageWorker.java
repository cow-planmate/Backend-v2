package com.planmate.common.externalAPI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.storage.GooglePlaceImageStorage;
import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Google Place 사진을 비동기로 받아 WebP로 변환해 MinIO에 올린다 — 검색 응답은 이 처리를
 * 기다리지 않는다(fire-and-forget). 클라이언트에 갱신을 알리는 메커니즘은 없다(v1 동일 동작,
 * 확정 — project_ai/04_기능/place/07_키워드검색_API_상세설계.md §5-3).
 *
 * {@code fetchAndUploadAsync}는 반드시 다른 빈(PlaceService 등)이 호출해야 한다 — 같은 클래스
 * 안에서 self-invocation하면 Spring AOP 프록시를 안 거쳐 {@code @Async}가 무시된다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GooglePlaceImageWorker {

    private static final String DETAILS_URL = "https://maps.googleapis.com/maps/api/place/details/json";
    private static final String PHOTO_URL = "https://maps.googleapis.com/maps/api/place/photo";
    private static final int MAX_PHOTO_DIMENSION = 400; // v1 계승

    @Qualifier("googlePlacesRestTemplate")
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final GooglePlaceImageStorage imageStorage;

    @Value("${api.google.key:}")
    private String googleApiKey;

    // 같은 placeId가 동시에 여러 요청에서 사진을 채우려 할 때 한 번만 처리(v1 계승).
    private final Set<String> processingIds = ConcurrentHashMap.newKeySet();

    @Async("googlePlaceImageExecutor")
    public void fetchAndUploadAsync(String placeId, String photoReference) {
        if (placeId == null || placeId.isBlank() || !processingIds.add(placeId)) {
            return;
        }
        try {
            String reference = (photoReference != null && !photoReference.isBlank())
                    ? photoReference
                    : fetchPhotoReference(placeId);
            if (reference == null) {
                return;
            }

            byte[] photoBytes = fetchPhotoBytes(reference);
            if (photoBytes == null || photoBytes.length == 0) {
                return;
            }

            byte[] webpBytes = convertToWebp(photoBytes);
            if (webpBytes == null) {
                return;
            }

            imageStorage.upload(placeId, webpBytes);
        } catch (Exception e) {
            log.warn("Google Place 사진 비동기 처리 실패 (placeId={}): {}", placeId, e.getMessage());
        } finally {
            processingIds.remove(placeId);
        }
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

    private byte[] fetchPhotoBytes(String photoReference) {
        try {
            String url = PHOTO_URL
                    + "?maxwidth=" + MAX_PHOTO_DIMENSION
                    + "&photoreference=" + URLEncoder.encode(photoReference, StandardCharsets.UTF_8)
                    + "&key=" + URLEncoder.encode(googleApiKey, StandardCharsets.UTF_8);
            ResponseEntity<byte[]> response = restTemplate.getForEntity(URI.create(url), byte[].class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Google Place Photo 다운로드 실패: {}", e.getMessage());
            return null;
        }
    }

    private byte[] convertToWebp(byte[] imageBytes) {
        try {
            ImmutableImage image = ImmutableImage.loader().fromBytes(imageBytes);
            // v1 계승 값 — quality 6, compression 80, 필터 강도 4, alpha 없음.
            return image.bytes(new WebpWriter(6, 80, 4, false));
        } catch (Exception e) {
            log.warn("WebP 변환 실패: {}", e.getMessage());
            return null;
        }
    }
}
