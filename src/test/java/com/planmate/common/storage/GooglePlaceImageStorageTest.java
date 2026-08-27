package com.planmate.common.storage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 로컬 MinIO(localhost:9000)를 대상으로 업로드/존재 확인을 검증한다.
 * project_ai/04_기능/place/06_키워드검색_API_인프라설계.md §4-2/§4-2-1.
 */
@SpringBootTest
class GooglePlaceImageStorageTest {

    @Autowired
    private GooglePlaceImageStorage googlePlaceImageStorage;

    @Test
    @DisplayName("upload 후 findExistingUrl로 같은 객체를 즉시 찾을 수 있다")
    void upload_thenFindExistingUrl_returnsSameUrl() {
        String placeId = "test-place-" + UUID.randomUUID();
        byte[] dummyBytes = "dummy-webp-bytes".getBytes(StandardCharsets.UTF_8);

        String uploadedUrl = googlePlaceImageStorage.upload(placeId, dummyBytes);

        assertThat(uploadedUrl).isNotNull();
        assertThat(uploadedUrl).contains("googleplace/" + placeId + ".webp");
        assertThat(googlePlaceImageStorage.findExistingUrl(placeId)).isEqualTo(uploadedUrl);
    }

    @Test
    @DisplayName("업로드된 적 없는 placeId는 findExistingUrl이 null을 반환한다(재조회 불필요 신호)")
    void findExistingUrl_neverUploaded_returnsNull() {
        String placeId = "never-uploaded-" + UUID.randomUUID();

        assertThat(googlePlaceImageStorage.findExistingUrl(placeId)).isNull();
    }

    @Test
    @DisplayName("업로드된 이미지는 실제 HTTP GET으로 공개 다운로드된다 (공개 읽기 버킷 정책 확인)")
    void uploadedImage_isPubliclyDownloadableOverHttp() {
        String placeId = "public-check-" + UUID.randomUUID();
        byte[] bytes = "hello-google-place-photo".getBytes(StandardCharsets.UTF_8);

        String url = googlePlaceImageStorage.upload(placeId, bytes);

        ResponseEntity<byte[]> response = new RestTemplate().getForEntity(url, byte[].class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(bytes);
    }
}
