package com.planmate.common.externalAPI;

import com.planmate.common.storage.GooglePlaceImageStorage;
import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 원본 이미지 응답과 분리하여 WebP 변환과 저장을 수행한다. */
@Component
@Slf4j
@RequiredArgsConstructor
public class GooglePlaceImageWorker {
    private final GooglePlaceImageStorage imageStorage;
    private final Set<String> processingIds = ConcurrentHashMap.newKeySet();

    @Async("googlePlaceImageExecutor")
    public void uploadAsync(String placeId, byte[] photoBytes) {
        if (!processingIds.add(placeId)) return;
        try {
            byte[] webpBytes = convertToWebp(photoBytes);
            if (webpBytes != null) imageStorage.upload(placeId, webpBytes);
        } catch (Exception e) {
            log.warn("Google Place 사진 저장 실패 (placeId={}): {}", placeId, e.getMessage());
        } finally {
            processingIds.remove(placeId);
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
