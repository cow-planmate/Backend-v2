package com.planmate.domain.destination.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DestinationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DestinationRepository destinationRepository;

    // ---------- GET /api/destination ----------

    @Test
    @DisplayName("여행지 목록 조회 성공 - 인증 헤더 없이도 실제 DB 시드 데이터를 그대로 반환한다")
    void getDestinations_success_noAuthRequired() throws Exception {
        List<Destination> seeded = destinationRepository.findAll();
        assertThat(seeded).isNotEmpty();

        String responseBody = mockMvc.perform(get("/api/destination"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        DestinationListResponse response = objectMapper.readValue(responseBody, DestinationListResponse.class);

        assertThat(response.destinations()).hasSize(seeded.size());

        Set<String> expectedNames = seeded.stream()
                .map(Destination::getDestinationName)
                .collect(Collectors.toSet());
        Set<String> actualNames = response.destinations().stream()
                .map(dto -> dto.destinationName())
                .collect(Collectors.toSet());
        assertThat(actualNames).isEqualTo(expectedNames);
        assertThat(actualNames).contains("서울", "부산");
    }

    @Test
    @DisplayName("여행지 목록 조회 성공 - 카테고리 필드(travelCategoryId 등) 없이 destinationId/destinationName만 응답한다")
    void getDestinations_success_responseShape() throws Exception {
        Destination seoul = destinationRepository.findByDestinationName("서울").orElseThrow();

        mockMvc.perform(get("/api/destination"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("\"destinationId\":" + seoul.getDestinationId());
                    assertThat(body).contains("\"destinationName\":\"서울\"");
                    assertThat(body).doesNotContain("travelCategoryId");
                    assertThat(body).doesNotContain("latitude");
                    assertThat(body).doesNotContain("longitude");
                });
    }
}
