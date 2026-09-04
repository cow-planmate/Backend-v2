package com.planmate.domain.chatbot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planmate.common.exception.domain.ChatbotAiMisconfiguredException;
import com.planmate.common.exception.domain.ChatbotAiUnavailableException;
import com.planmate.domain.chatbot.client.AiChatbotClient;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗봇 대화 턴 엔드포인트 통합 (설계 §9 / 표준 §16.5).
 *
 * <p>planmate-ai 호출({@link AiChatbotClient})은 {@code @MockitoBean} 으로 스텁한다 — HTTP 레벨
 * 상태/재시도 매핑은 {@code AiChatbotClientTest}(MockRestServiceServer)가 이미 커버한다. 여기서는
 * 실제 Spring 스택(게이트웨이 신원 인증 → {@code @PreAuthorize} → 검증 → 컨트롤러 → 서비스 →
 * 실 DB userContext 조립 → raw 패스스루)이 붙는지를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatbotIntegrationTest {

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
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanMemberRepository planMemberRepository;

    @MockitoBean
    private AiChatbotClient aiChatbotClient;

    private User owner;
    private String ownerToken;
    private Plan plan;

    @BeforeEach
    void setUp() {
        owner = saveUser("chatbot-owner");
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());
        Destination busan = destinationRepository.findByDestinationName("부산").orElseThrow();
        plan = savePlan(owner, busan);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------- fixtures ----------

    private User saveUser(String prefix) {
        return userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .nickname(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
    }

    private Plan savePlan(User planOwner, Destination destination) {
        Plan saved = planRepository.saveAndFlush(Plan.builder()
                .planName("부산 여행")
                .adultCount(2)
                .childCount(0)
                .isShared(false)
                .user(planOwner)
                .destination(destination)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(saved)
                .user(planOwner)
                .role(PlanMemberRole.OWNER)
                .build());
        return saved;
    }

    private MvcResult postChat(UUID planId, String token, String bodyJson) throws Exception {
        return mockMvc.perform(post("/api/plan/{planId}/chatbot", planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andReturn();
    }

    private static final String MINIMAL_BODY = "{\"message\":\"부산 카페 추천해줘\"}";

    // ---------- 200 raw 패스스루 ----------

    @Test
    @DisplayName("200 — planmate-ai 응답 JSON 을 (planmate-ai 가 나중에 추가할 미지 필드까지) byte 동일하게 프론트로 전달한다")
    void passthrough_rawBodyIncludingUnknownFields() throws Exception {
        JsonNode aiResponse = objectMapper.readTree(
                "{\"userMessage\":\"부산 카페 추천이에요\",\"plan\":null,\"shownPlaces\":[{\"contentId\":\"1\"}]," +
                        "\"futureFieldFromAi\":{\"nested\":true}}");
        given(aiChatbotClient.call(any(ObjectNode.class), eq(plan.getPlanId()))).willReturn(aiResponse);

        MvcResult result = postChat(plan.getPlanId(), ownerToken, MINIMAL_BODY);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode returned = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(returned).isEqualTo(aiResponse);   // 미지 필드 포함 완전 동일
    }

    @Test
    @DisplayName("200 — plan 이 채워진 응답도 그대로 통과")
    void passthrough_withPlan() throws Exception {
        JsonNode aiResponse = objectMapper.readTree(
                "{\"userMessage\":\"짰어요\",\"plan\":{\"planFrame\":{\"planId\":\"x\"},\"timetables\":[],\"placeBlocks\":[]}," +
                        "\"shownPlaces\":[]}");
        given(aiChatbotClient.call(any(), any())).willReturn(aiResponse);

        MvcResult result = postChat(plan.getPlanId(), ownerToken, MINIMAL_BODY);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())).isEqualTo(aiResponse);
    }

    // ---------- 실 DB userContext 조립 (표준 §16.5 핵심 도메인 동작) ----------

    @Test
    @DisplayName("userContext — 실제 destination_region_code 시드(부산=26/시군구없음)로 조립돼 planmate-ai 로 전달된다")
    void userContext_assembledFromRealSeed() throws Exception {
        given(aiChatbotClient.call(any(), any())).willReturn(objectMapper.createObjectNode());

        postChat(plan.getPlanId(), ownerToken, MINIMAL_BODY);

        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(aiChatbotClient).call(captor.capture(), eq(plan.getPlanId()));
        JsonNode body = captor.getValue();

        assertThat(body.get("planId").asText()).isEqualTo(plan.getPlanId().toString());
        assertThat(body.get("message").asText()).isEqualTo("부산 카페 추천해줘");
        JsonNode uc = body.get("userContext");
        assertThat(uc.get("regionCodes")).hasSize(1);
        assertThat(uc.get("regionCodes").get(0).get("ldongRegnCd").asText()).isEqualTo("26");
        assertThat(uc.get("regionCodes").get(0).get("ldongSignguCd").isNull()).isTrue();
        assertThat(uc.get("signguCodes")).isEmpty();          // 광역시 → 빈 배열
        assertThat(uc.get("preferredThemes")).isEmpty();       // 선호테마 미설정
        assertThat(body.get("recentMessages").isArray()).isTrue();
        assertThat(body.get("recentMessages")).isEmpty();      // 누락 → 빈 배열
        assertThat(body.get("pendingContext").isNull()).isTrue();
    }

    // ---------- 에러 매핑 ----------

    @Test
    @DisplayName("AI 불가(502/503/timeout) → 503 CHATBOT_001")
    void aiUnavailable_503() throws Exception {
        given(aiChatbotClient.call(any(), any())).willThrow(new ChatbotAiUnavailableException());

        mockMvc.perform(post("/api/plan/{planId}/chatbot", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MINIMAL_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHATBOT_001"));
    }

    @Test
    @DisplayName("AI 설정 오류(401/422) → 500 CHATBOT_002")
    void aiMisconfigured_500() throws Exception {
        given(aiChatbotClient.call(any(), any())).willThrow(new ChatbotAiMisconfiguredException());

        mockMvc.perform(post("/api/plan/{planId}/chatbot", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MINIMAL_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CHATBOT_002"));
    }

    // ---------- 인가 / 검증 ----------

    @Test
    @DisplayName("플랜 멤버가 아니면 403 PLAN_002, planmate-ai 는 호출되지 않는다")
    void notMember_403() throws Exception {
        User stranger = saveUser("chatbot-stranger");
        Plan otherPlan = savePlan(stranger, destinationRepository.findByDestinationName("부산").orElseThrow());

        mockMvc.perform(post("/api/plan/{planId}/chatbot", otherPlan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MINIMAL_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_002"));

        verify(aiChatbotClient, never()).call(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 플랜이면 404 PLAN_001")
    void planNotFound_404() throws Exception {
        mockMvc.perform(post("/api/plan/{planId}/chatbot", UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MINIMAL_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_001"));
    }

    @Test
    @DisplayName("message 가 비면 400 COMMON_001, planmate-ai 는 호출되지 않는다")
    void blankMessage_400() throws Exception {
        mockMvc.perform(post("/api/plan/{planId}/chatbot", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verify(aiChatbotClient, never()).call(any(), any());
    }

    @Test
    @DisplayName("recentMessages 가 4개면 400 (@Size max=3)")
    void tooManyRecentMessages_400() throws Exception {
        mockMvc.perform(post("/api/plan/{planId}/chatbot", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\",\"recentMessages\":[\"a\",\"b\",\"c\",\"d\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verify(aiChatbotClient, never()).call(any(), any());
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void noToken_401() throws Exception {
        mockMvc.perform(post("/api/plan/{planId}/chatbot", plan.getPlanId())
                        .contentType(MediaType.APPLICATION_JSON).content(MINIMAL_BODY))
                .andExpect(status().isUnauthorized());
    }
}
