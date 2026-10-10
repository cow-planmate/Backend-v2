package com.planmate.domain.destination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planmate.domain.chatbot.client.AiChatbotClient;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계층 상세로 새로 생긴 노드를 고른 뒤의 흐름 — 플랜 생성·조회, 일정 가져가기(전체 생성), 챗봇 userContext,
 * 날씨가 기존 경로 그대로 동작하는지 실제 DB로 확인한다(B_계층_상세.md §2-3·§6).
 *
 * <p>노드 유형 3가지를 고루 쓴다: 도 시도 노드(경기, 매핑 (41, NULL)) / 일반 시군(파주, (41, 480)) /
 * 묶음(화성, 대표+구 4개). 외부 호출(planmate-ai, Open-Meteo)만 목킹한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DestinationHierarchyFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DestinationRepository destinationRepository;

    @MockitoBean
    private AiChatbotClient aiChatbotClient;

    @MockitoBean
    private RestTemplate openMeteoRestTemplate;

    private String token;

    @BeforeEach
    void setUp() {
        User user = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("hierarchy-" + UUID.randomUUID() + "@example.com")
                .nickname("hier-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        token = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 플랜 생성 → 조회 ----------

    @Test
    @DisplayName("플랜 프레임 생성 - 신규 노드(경기·파주·화성)로 만들면 플랜 이름 기본값이 노드 이름, 조회 시 그 목적지가 나온다")
    void createPlanFrame_withNewNodes() throws Exception {
        for (String name : List.of("경기", "파주", "화성")) {
            Destination node = node(name);

            String created = mockMvc.perform(post("/api/plan")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"destinationId": %d, "dates": ["%s"], "adultCount": 2, "childCount": 0}
                                    """.formatted(node.getDestinationId(), LocalDate.now().plusDays(7))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String planId = objectMapper.readTree(created).get("planId").asText();

            JsonNode planFrame = completePlan(planId).get("planFrame");
            assertThat(planFrame.get("destinationId").asInt()).as(name).isEqualTo(node.getDestinationId());
            assertThat(planFrame.get("destinationName").asText()).as(name).isEqualTo(name);
            assertThat(planFrame.get("planName").asText()).as(name).isEqualTo(name);
        }
    }

    @Test
    @DisplayName("일정 가져가기 경로(POST /api/plan/full) - 신규 노드 destinationId로 전체 플랜 생성·조회")
    void createFullPlan_withNewNode() throws Exception {
        Destination hwaseong = node("화성");

        String created = mockMvc.perform(post("/api/plan/full")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planFrame": {"destinationId": %d, "adultCount": 1, "childCount": 0},
                                 "timetables": [], "timetablePlaceBlocks": []}
                                """.formatted(hwaseong.getDestinationId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode planFrame = completePlan(objectMapper.readTree(created).get("planId").asText()).get("planFrame");
        assertThat(planFrame.get("destinationId").asInt()).isEqualTo(hwaseong.getDestinationId());
        assertThat(planFrame.get("destinationName").asText()).isEqualTo("화성");
    }

    // ---------- 챗봇 userContext ----------

    @Test
    @DisplayName("챗봇 - 도 시도 노드(경기)는 regionCodes [41/NULL], signguCodes 빈 값 → AI가 경기도 전체로 검색")
    void chatbot_sidoNode_userContext() throws Exception {
        JsonNode userContext = chatbotUserContextFor("경기");

        assertThat(regionCodes(userContext)).containsExactly("41/null");
        assertThat(userContext.get("signguCodes")).isEmpty();
    }

    @Test
    @DisplayName("챗봇 - 일반 시군(파주)은 regionCodes [41/480], signguCodes [480]")
    void chatbot_sigunguNode_userContext() throws Exception {
        JsonNode userContext = chatbotUserContextFor("파주");

        assertThat(regionCodes(userContext)).containsExactly("41/480");
        assertThat(texts(userContext.get("signguCodes"))).containsExactly("480");
    }

    @Test
    @DisplayName("챗봇 - 묶음 노드(화성)는 대표코드+구 4개가 regionCodes·signguCodes에 모두 실린다")
    void chatbot_bundleNode_userContext() throws Exception {
        JsonNode userContext = chatbotUserContextFor("화성");

        assertThat(regionCodes(userContext))
                .containsExactlyInAnyOrder("41/590", "41/591", "41/593", "41/595", "41/597");
        assertThat(texts(userContext.get("signguCodes")))
                .containsExactlyInAnyOrder("590", "591", "593", "595", "597");
    }

    // ---------- 날씨 ----------

    @Test
    @DisplayName("날씨 - 신규 노드(파주·경기)의 좌표가 그대로 Open-Meteo 요청에 실린다")
    void weather_usesNewNodeCoordinates() throws Exception {
        LocalDate date = LocalDate.now().plusDays(1);
        given(openMeteoRestTemplate.getForEntity(anyString(), eq(String.class)))
                .willReturn(new ResponseEntity<>("{\"daily\":{\"time\":[\"" + date + "\"],\"weathercode\":[0],"
                        + "\"temperature_2m_max\":[20.0],\"temperature_2m_min\":[10.0],"
                        + "\"apparent_temperature_max\":[19.0]}}", HttpStatus.OK));

        List<String> expectedCoords = new ArrayList<>();
        for (String name : List.of("파주", "경기")) {
            Destination node = node(name);
            expectedCoords.add("latitude=" + node.getLatitude().toPlainString()
                    + "&longitude=" + node.getLongitude().toPlainString());

            mockMvc.perform(get("/api/weather")
                            .param("destinationId", String.valueOf(node.getDestinationId()))
                            .param("startDate", date.toString())
                            .param("endDate", date.toString()))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(openMeteoRestTemplate, org.mockito.Mockito.times(2)).getForEntity(url.capture(), eq(String.class));
        assertThat(url.getAllValues().get(0)).contains(expectedCoords.get(0));
        assertThat(url.getAllValues().get(1)).contains(expectedCoords.get(1));
        // 파주는 시청 부근(37.76, 126.78) — 좌표표 §4
        assertThat(node("파주").getLatitude().doubleValue()).isBetween(37.7, 37.8);
    }

    // ---------- helpers ----------

    private Destination node(String name) {
        return destinationRepository.findByDestinationName(name).orElseThrow();
    }

    private JsonNode completePlan(String planId) throws Exception {
        String body = mockMvc.perform(get("/api/plan/{planId}/complete", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode chatbotUserContextFor(String nodeName) throws Exception {
        String created = mockMvc.perform(post("/api/plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"destinationId": %d, "dates": ["%s"], "adultCount": 2, "childCount": 0}
                                """.formatted(node(nodeName).getDestinationId(), LocalDate.now().plusDays(7))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String planId = objectMapper.readTree(created).get("planId").asText();

        given(aiChatbotClient.call(any(), any())).willReturn(objectMapper.createObjectNode());
        mockMvc.perform(post("/api/plan/{planId}/chatbot", planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"추천해줘\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(aiChatbotClient).call(captor.capture(), eq(UUID.fromString(planId)));
        JsonNode body = captor.getValue();
        // AI가 받는 플랜 문맥에도 선택한 노드 이름이 실린다
        assertThat(body.get("planContext").get("planFrame").get("destinationName").asText()).isEqualTo(nodeName);
        return body.get("userContext");
    }

    private static List<String> regionCodes(JsonNode userContext) {
        List<String> result = new ArrayList<>();
        for (JsonNode rc : userContext.get("regionCodes")) {
            JsonNode signgu = rc.get("ldongSignguCd");
            result.add(rc.get("ldongRegnCd").asText() + "/" + (signgu == null || signgu.isNull() ? "null" : signgu.asText()));
        }
        return result;
    }

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asText()));
        return result;
    }
}
