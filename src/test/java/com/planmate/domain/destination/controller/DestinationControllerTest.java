package com.planmate.domain.destination.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.dto.DestinationDto;
import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 여행지 1차 목록·트리 통합 테스트 — 실제 DB(V16 시드) 기준. B_계층_상세.md §4-6·§5.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DestinationControllerTest {

    // §4-6 결과 1차 목록 순서(광역시 먼저, 이어서 도별 묶음)
    private static final List<String> FEATURED_ORDER = List.of(
            "서울", "부산", "대구", "인천", "대전", "울산",
            "수원", "가평", "양평",
            "춘천", "강릉", "속초", "평창", "양양",
            "단양",
            "공주", "보령", "부여", "태안",
            "전주", "군산",
            "광주", "목포", "여수", "순천", "담양",
            "포항", "경주", "안동",
            "통영", "거제", "남해",
            "제주", "서귀포");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DestinationRepository destinationRepository;

    // ---------- GET /api/destination ----------

    @Test
    @DisplayName("1차 목록 조회 성공 - 인증 없이 featured 노드 34개를 §4-6 순서로 반환하고 DB의 featured 집합과 일치한다")
    void getDestinations_success_featuredInOrder() throws Exception {
        String body = mockMvc.perform(get("/api/destination"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn().getResponse().getContentAsString();

        DestinationListResponse response = objectMapper.readValue(body, DestinationListResponse.class);

        assertThat(response.destinations())
                .extracting(DestinationDto::destinationName)
                .containsExactlyElementsOf(FEATURED_ORDER);

        Set<Integer> featuredIdsInDb = destinationRepository.findAll().stream()
                .filter(Destination::isFeatured)
                .map(Destination::getDestinationId)
                .collect(Collectors.toSet());
        assertThat(response.destinations())
                .extracting(DestinationDto::destinationId)
                .containsExactlyInAnyOrderElementsOf(featuredIdsInDb);
    }

    @Test
    @DisplayName("1차 목록 조회 성공 - 응답 형식은 그대로(destinationId/destinationName만, 좌표·트리 필드 없음)")
    void getDestinations_success_responseShape() throws Exception {
        Destination seoul = destinationRepository.findByDestinationName("서울").orElseThrow();

        String body = mockMvc.perform(get("/api/destination"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"destinationId\":" + seoul.getDestinationId());
        assertThat(body).contains("\"destinationName\":\"서울\"");
        assertThat(body).doesNotContain("latitude", "longitude", "featured", "children", "parent");
    }

    // ---------- GET /api/destination/tree ----------

    @Test
    @DisplayName("트리 조회 성공 - 인증 없이 시도 16개 → 시군 154개, 비노출 노드까지 전부 내려준다")
    void getDestinationTree_success_wholeTree() throws Exception {
        String body = mockMvc.perform(get("/api/destination/tree"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn().getResponse().getContentAsString();

        JsonNode sidos = objectMapper.readTree(body).get("sidos");
        List<String> sidoNames = new ArrayList<>();
        int sigunguCount = 0;
        int featuredCount = 0;
        for (JsonNode sido : sidos) {
            sidoNames.add(sido.get("destinationName").asText());
            featuredCount += sido.get("featured").asBoolean() ? 1 : 0;
            for (JsonNode child : sido.get("children")) {
                sigunguCount++;
                featuredCount += child.get("featured").asBoolean() ? 1 : 0;
                assertThat(child.has("children")).as("시군 노드엔 children 키가 없다").isFalse();
            }
        }

        assertThat(sidoNames).containsExactly(
                "서울", "부산", "대구", "인천", "대전", "울산", "세종",
                "경기", "강원", "충북", "충남", "전북", "전남광주", "경북", "경남", "제주도");
        assertThat(sigunguCount).isEqualTo(154);
        assertThat(featuredCount).isEqualTo(34);
        assertThat(destinationRepository.count()).isEqualTo(16 + 154);
    }

    @Test
    @DisplayName("트리 조회 성공 - 광역시는 빈 children, 제주도 아래 기존 제주·서귀포(ID 유지), 경기 아래 파주·화성·경기 광주")
    void getDestinationTree_success_nodes() throws Exception {
        String body = mockMvc.perform(get("/api/destination/tree"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode sidos = objectMapper.readTree(body).get("sidos");

        JsonNode seoul = findByName(sidos, "서울");
        assertThat(seoul.get("children").isArray()).isTrue();
        assertThat(seoul.get("children")).isEmpty();
        assertThat(seoul.get("destinationId").asInt()).isEqualTo(1);

        JsonNode jeju = findByName(sidos, "제주도");
        assertThat(jeju.get("children")).extracting(n -> n.get("destinationName").asText())
                .containsExactly("제주", "서귀포");
        assertThat(jeju.get("children")).extracting(n -> n.get("destinationId").asInt())
                .containsExactly(27, 28);

        JsonNode gyeonggi = findByName(sidos, "경기");
        assertThat(gyeonggi.get("featured").asBoolean()).isFalse();
        assertThat(gyeonggi.get("children")).extracting(n -> n.get("destinationName").asText())
                .contains("수원", "파주", "화성", "경기 광주")
                .doesNotContain("수원시", "장안구");
    }

    // ---------- ETag / 304 ----------

    @Test
    @DisplayName("조건부 요청 - 받은 ETag 그대로 보내면 304(본문 없음), Cloudflare식 W/ 약한 형태여도 304")
    void conditionalRequest_sameEtag_notModified() throws Exception {
        for (String path : List.of("/api/destination", "/api/destination/tree")) {
            MvcResult first = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
            String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(etag).isNotBlank();

            MvcResult notModified = mockMvc.perform(get(path).header(HttpHeaders.IF_NONE_MATCH, etag))
                    .andExpect(status().isNotModified())
                    .andReturn();
            assertThat(notModified.getResponse().getContentAsString()).isEmpty();
            assertThat(notModified.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(etag);

            mockMvc.perform(get(path).header(HttpHeaders.IF_NONE_MATCH, "W/" + etag))
                    .andExpect(status().isNotModified());
        }
    }

    @Test
    @DisplayName("조건부 요청 - ETag가 다르면(데이터가 바뀐 경우) 200 + 새 본문")
    void conditionalRequest_differentEtag_ok() throws Exception {
        String body = mockMvc.perform(get("/api/destination/tree")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"stale-etag\""))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("sidos")).hasSize(16);
    }

    @Test
    @DisplayName("1차 목록과 트리는 서로 다른 ETag — 한쪽 ETag로 다른 쪽을 조건부 요청하면 200")
    void conditionalRequest_etagsAreIndependent() throws Exception {
        String listEtag = mockMvc.perform(get("/api/destination"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get("/api/destination/tree").header(HttpHeaders.IF_NONE_MATCH, listEtag))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CORS - 허용 오리진 요청에 ETag 헤더를 노출한다(Access-Control-Expose-Headers)")
    void cors_exposesEtag() throws Exception {
        mockMvc.perform(get("/api/destination").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.containsString("ETag")));
    }

    private static JsonNode findByName(JsonNode nodes, String name) {
        for (JsonNode node : nodes) {
            if (name.equals(node.get("destinationName").asText())) {
                return node;
            }
        }
        throw new AssertionError("노드 없음: " + name);
    }
}
