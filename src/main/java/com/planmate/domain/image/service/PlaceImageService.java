package com.planmate.domain.image.service;

import com.planmate.common.externalAPI.GooglePlaceImageWorker;
import com.planmate.common.externalAPI.GooglePlacePhotoClient;
import com.planmate.common.storage.GooglePlaceImageStorage;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class PlaceImageService {
    private final GooglePlaceImageStorage storage;
    private final GooglePlacePhotoClient photoClient;
    private final GooglePlaceImageWorker worker;

    public ResponseEntity<byte[]> getImage(String placeId) {
        byte[] cached = storage.read(placeId);
        if (cached != null && cached.length > 0) {
            return image(cached, MediaType.parseMediaType("image/webp"));
        }
        ResponseEntity<byte[]> photo = photoClient.fetch(placeId);
        if (photo == null || photo.getBody() == null || photo.getBody().length == 0) {
            return ResponseEntity.notFound().build();
        }
        MediaType contentType = photo.getHeaders().getContentType();
        if (contentType == null || !"image".equals(contentType.getType())) {
            return ResponseEntity.status(502).build();
        }
        try {
            worker.uploadAsync(placeId, photo.getBody());
        } catch (RuntimeException e) {
            // 작업 큐가 가득 차도 이미 받은 원본 사진은 응답한다.
            log.warn("사진 저장 예약 실패 (placeId={}): {}", placeId, e.getMessage());
        }
        return image(photo.getBody(), contentType);
    }

    private ResponseEntity<byte[]> image(byte[] bytes, MediaType type) {
        return ResponseEntity.ok().contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(bytes);
    }
}
