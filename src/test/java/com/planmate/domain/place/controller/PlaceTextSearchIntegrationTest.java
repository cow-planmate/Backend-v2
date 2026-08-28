package com.planmate.domain.place.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.storage.GooglePlaceImageStorage;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.place.dto.PlaceTextSearchNextRequest;
import com.planmate.domain.place.dto.PlaceTextSearchResponse;
import com.planmate.domain.place.dto.PlaceTextSearchResultDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Google Places API + 실제 DB(destination 시드) + 실제 MinIO를 전부 붙여서 검증한다.
 * 네트워크/외부 API 의존이라 다른 테스트보다 느리고, Google 응답 자체는 결정적이지 않으므로
 * 구조적인 것(필드가 비어있지 않은지, 상태코드, 실제 파이프라인이 끝까지 도는지)만 검증한다.
 * project_ai/04_기능/place/07_키워드검색_API_상세설계.md
 *
 * <p>CI에는 실제 Google API 키/MinIO가 없어 "external" 태그로 빼뒀다(기본 test 태스크에서
 * 제외, ./gradlew externalTest로 로컬에서 인프라 갖춰놓고 수동 실행). build.gradle 참고.
 */
@Tag("external")
@SpringBootTest
@AutoConfigureMockMvc
class PlaceTextSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DestinationRepository destinationRepository;

    @Autowired
    private GooglePlaceImageStorage googlePlaceImageStorage;

    @Test
    @DisplayName("GET /api/place/text-search - 실제 Google API 호출, destinationId 없이 순수 텍스트 검색")
    void searchByText_realGoogleApi_withoutDestinationId() throws Exception {
        String body = mockMvc.perform(get("/api/place/text-search").param("query", "경복궁"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        PlaceTextSearchResponse response = objectMapper.readValue(body, PlaceTextSearchResponse.class);

        assertThat(response.places()).isNotEmpty();
        PlaceTextSearchResultDto first = response.places().get(0);
        assertThat(first.placeId()).isNotBlank();
        assertThat(first.name()).isNotBlank();
        assertThat(first.latitude()).isBetween(-90.0, 90.0);
        assertThat(first.longitude()).isBetween(-180.0, 180.0);
    }

    @Test
    @DisplayName("GET /api/place/text-search - destinationId(실제 DB 시드, 서울)를 넘겨도 정상 동작")
    void searchByText_realGoogleApi_withRealDestinationIdBias() throws Exception {
        Destination seoul = destinationRepository.findByDestinationName("서울").orElseThrow();

        String body = mockMvc.perform(get("/api/place/text-search")
                        .param("query", "카페")
                        .param("destinationId", String.valueOf(seoul.getDestinationId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        PlaceTextSearchResponse response = objectMapper.readValue(body, PlaceTextSearchResponse.class);

        assertThat(response.places()).isNotEmpty();
    }

    @Test
    @DisplayName("GET /api/place/text-search - 인증 헤더 없이도 200 (인증 불필요 확정)")
    void searchByText_noAuthHeader_stillSucceeds() throws Exception {
        mockMvc.perform(get("/api/place/text-search").param("query", "서울역"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/place/text-search - 존재하지 않는 destinationId는 404(DEST_001)")
    void searchByText_invalidDestinationId_returnsDestinationNotFound() throws Exception {
        mockMvc.perform(get("/api/place/text-search")
                        .param("query", "아무거나")
                        .param("destinationId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("DEST_001"));
    }

    @Test
    @DisplayName("GET /api/place/text-search - query 없이 요청하면 400")
    void searchByText_blankQuery_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/place/text-search").param("query", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검색 → 비동기 사진 채움까지 실제로 끝까지 돈다 (실 Google API + 실 MinIO)")
    void searchByText_asyncImagePipeline_actuallyUploadsToRealMinio() throws Exception {
        // 사진이 있을 확률이 높은 유명 체인/랜드마크로 검색한다.
        String body = mockMvc.perform(get("/api/place/text-search").param("query", "스타벅스 강남역"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        PlaceTextSearchResponse response = objectMapper.readValue(body, PlaceTextSearchResponse.class);
        assertThat(response.places()).isNotEmpty();

        // 비동기 파이프라인(Details→Photo→WebP 변환→MinIO 업로드)이 끝날 때까지 폴링한다.
        String uploadedUrl = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (PlaceTextSearchResultDto place : response.places()) {
                String url = googlePlaceImageStorage.findExistingUrl(place.placeId());
                if (url != null) {
                    uploadedUrl = url;
                    break;
                }
            }
            if (uploadedUrl != null) {
                break;
            }
            Thread.sleep(1_000);
        }

        // 검색된 장소 중 최소 하나는 사진이 있어서 MinIO에 올라갔어야 한다.
        // (모든 결과가 사진 자체가 없는 극단적 경우는 배제 — 강남역 스타벅스류는 통상 사진이 있음)
        assertThat(uploadedUrl)
                .as("비동기 이미지 파이프라인이 20초 내에 최소 한 장은 MinIO에 업로드해야 한다")
                .isNotNull();
        assertThat(uploadedUrl).contains("/googleplace/");
    }

    @Test
    @DisplayName("POST /api/place/text-search/next - 실제 nextPageToken으로 다음 페이지를 받아온다")
    void searchByTextNextPage_realToken_returnsNextPage() throws Exception {
        // 결과가 20건을 넘어 다음 페이지가 생길 만한 넓은 검색어를 쓴다.
        String firstBody = mockMvc.perform(get("/api/place/text-search").param("query", "카페"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        PlaceTextSearchResponse firstPage = objectMapper.readValue(firstBody, PlaceTextSearchResponse.class);

        if (firstPage.nextPageToken() == null) {
            // Google이 이번 검색엔 다음 페이지를 안 줬다면(응답은 결정적이지 않음) 이 케이스는 건너뛴다.
            return;
        }

        // Google 토큰은 발급 직후 몇 초간 비활성 상태다(06_키워드검색_API_인프라설계.md §3-3) — 즉시
        // 호출하면 INVALID_REQUEST가 날 수 있어 활성화를 기다린다.
        Thread.sleep(3_000);

        PlaceTextSearchNextRequest nextRequest = new PlaceTextSearchNextRequest("카페", firstPage.nextPageToken());
        String nextBody = mockMvc.perform(post("/api/place/text-search/next")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nextRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        PlaceTextSearchResponse nextPage = objectMapper.readValue(nextBody, PlaceTextSearchResponse.class);

        assertThat(nextPage.places()).isNotEmpty();
    }
}
