package com.planmate.domain.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planmate.common.exception.domain.ChatbotAiUnavailableException;
import com.planmate.common.exception.domain.PlanAccessDeniedException;
import com.planmate.domain.chatbot.client.AiChatbotClient;
import com.planmate.domain.chatbot.dto.ChatbotTurnRequest;
import com.planmate.domain.destination.cache.DestinationRegionCodeCache;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.dto.PlanFrameDetailDto;
import com.planmate.domain.plan.service.PlanService;
import com.planmate.domain.theme.cache.PreferredThemeLclsSystmCache;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.PreferredThemeLclsSystmCode;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ChatbotServiceTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final int DESTINATION_ID = 20;

    @Mock
    private PlanService planService;
    @Mock
    private DestinationRegionCodeCache destinationRegionCodeCache;
    @Mock
    private PreferredThemeLclsSystmCache preferredThemeLclsSystmCache;
    @Mock
    private UserPreferredThemeRepository userPreferredThemeRepository;
    @Mock
    private AiChatbotClient aiChatbotClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatbotService chatbotService;

    @BeforeEach
    void setUp() {
        chatbotService = new ChatbotService(planService, destinationRegionCodeCache,
                preferredThemeLclsSystmCache, userPreferredThemeRepository, aiChatbotClient, objectMapper);
    }

    // ---------- fixtures ----------

    private GetCompletePlanResponse completePlan() {
        PlanFrameDetailDto frame = new PlanFrameDetailDto(
                PLAN_ID, "부산 여행", DESTINATION_ID, "부산", 2, 0);
        return new GetCompletePlanResponse(frame, List.of(), List.of());
    }

    private ChatbotTurnRequest request(JsonNode pending, JsonNode shown, List<String> recent) {
        return new ChatbotTurnRequest("부산 카페 추천해줘", pending, shown, recent);
    }

    private DestinationRegionCode regionCode(String regn, String signgu) {
        return DestinationRegionCode.builder().ldongRegnCd(regn).ldongSignguCd(signgu).build();
    }

    private UserPreferredTheme userTheme(int themeId) {
        return UserPreferredTheme.builder()
                .preferredTheme(PreferredTheme.builder().preferredThemeId(themeId).build())
                .build();
    }

    private PreferredThemeLclsSystmCode lclsCode(int level, String code) {
        return PreferredThemeLclsSystmCode.builder().lclsSystmLevel(level).lclsSystmCd(code).build();
    }

    private void stubPlan() {
        given(planService.getCompletePlan(USER_ID, PLAN_ID)).willReturn(completePlan());
    }

    private ObjectNode capturedBody() {
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        org.mockito.Mockito.verify(aiChatbotClient).call(captor.capture(), eq(PLAN_ID));
        return captor.getValue();
    }

    // ---------- 성공 + 바디 조립 ----------

    @Test
    @DisplayName("성공 — planId/message/planContext/pendingContext/shownPlaces/userContext/recentMessages 7필드를 조립해 호출하고 응답 JSON 을 그대로 반환한다")
    void chat_assemblesBodyAndPassesResponseThrough() {
        stubPlan();
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("26", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of());

        JsonNode pending = objectMapper.createObjectNode().put("marker", "workingCopy");
        JsonNode shown = objectMapper.createArrayNode().add(
                objectMapper.createObjectNode().put("contentId", "123"));
        JsonNode aiResponse = objectMapper.createObjectNode().put("userMessage", "추천드려요").putNull("plan");
        given(aiChatbotClient.call(any(ObjectNode.class), eq(PLAN_ID))).willReturn(aiResponse);

        JsonNode result = chatbotService.chat(PLAN_ID, USER_ID,
                request(pending, shown, List.of("부산 바다 보이는 카페", "이거 말고")));

        assertThat(result).isSameAs(aiResponse);

        ObjectNode body = capturedBody();
        assertThat(body.get("planId").asText()).isEqualTo(PLAN_ID.toString());
        assertThat(body.get("message").asText()).isEqualTo("부산 카페 추천해줘");
        assertThat(body.get("planContext").get("planFrame").get("destinationId").asInt()).isEqualTo(DESTINATION_ID);
        assertThat(body.get("pendingContext").get("marker").asText()).isEqualTo("workingCopy");
        assertThat(body.get("shownPlaces").get(0).get("contentId").asText()).isEqualTo("123");
        assertThat(body.get("userContext").has("preferredThemes")).isTrue();
        assertThat(body.get("recentMessages")).hasSize(2);
        assertThat(body.get("recentMessages").get(0).asText()).isEqualTo("부산 바다 보이는 카페");
    }

    // ---------- userContext 조립 분기 ----------

    @Test
    @DisplayName("userContext — 광역시(시군구코드 전부 null) → signguCodes 는 빈 배열, regionCodes 는 실림")
    void userContext_metropolitanCity_emptySignguCodes() {
        stubPlan();
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("26", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of());
        given(aiChatbotClient.call(any(), any())).willReturn(objectMapper.createObjectNode());

        chatbotService.chat(PLAN_ID, USER_ID, request(null, null, null));

        JsonNode uc = capturedBody().get("userContext");
        assertThat(uc.get("signguCodes")).isEmpty();
        assertThat(uc.get("regionCodes")).hasSize(1);
        assertThat(uc.get("regionCodes").get(0).get("ldongRegnCd").asText()).isEqualTo("26");
        assertThat(uc.get("regionCodes").get(0).get("ldongSignguCd").isNull()).isTrue();
    }

    @Test
    @DisplayName("userContext — 시군구 분할 목적지(대표코드+구들) → signguCodes 에 non-null 코드만 distinct")
    void userContext_multiSigngu_distinctNonNull() {
        stubPlan();
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(
                regionCode("41", "110"), regionCode("41", "111"), regionCode("41", "113"),
                regionCode("41", "115"), regionCode("41", "117")));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of());
        given(aiChatbotClient.call(any(), any())).willReturn(objectMapper.createObjectNode());

        chatbotService.chat(PLAN_ID, USER_ID, request(null, null, null));

        JsonNode uc = capturedBody().get("userContext");
        assertThat(uc.get("regionCodes")).hasSize(5);
        assertThat(uc.get("signguCodes")).hasSize(5);
        assertThat(uc.get("signguCodes").get(0).asText()).isEqualTo("110");
    }

    @Test
    @DisplayName("userContext — 선호 테마 lclsSystm 평탄화(레벨+코드), 카테고리 필터 없이 전량 + distinct")
    void userContext_preferredThemesFlattened() {
        stubPlan();
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("26", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID))
                .willReturn(List.of(userTheme(1), userTheme(2)));
        given(preferredThemeLclsSystmCache.get(1)).willReturn(List.of(lclsCode(1, "HS")));
        given(preferredThemeLclsSystmCache.get(2)).willReturn(List.of(lclsCode(2, "NA010200"), lclsCode(1, "HS")));
        given(aiChatbotClient.call(any(), any())).willReturn(objectMapper.createObjectNode());

        chatbotService.chat(PLAN_ID, USER_ID, request(null, null, null));

        JsonNode themes = capturedBody().get("userContext").get("preferredThemes");
        assertThat(themes).hasSize(2);   // (1,HS) 중복 제거
        assertThat(themes.get(0).get("level").asInt()).isEqualTo(1);
        assertThat(themes.get(0).get("code").asText()).isEqualTo("HS");
    }

    @Test
    @DisplayName("userContext — 선호 테마 없음 → preferredThemes 빈 배열")
    void userContext_noThemes_emptyArray() {
        stubPlan();
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("26", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of());
        given(aiChatbotClient.call(any(), any())).willReturn(objectMapper.createObjectNode());

        chatbotService.chat(PLAN_ID, USER_ID, request(null, null, null));

        assertThat(capturedBody().get("userContext").get("preferredThemes")).isEmpty();
    }

    // ---------- 프론트 저작 필드 통과 ----------

    @Test
    @DisplayName("pendingContext/shownPlaces 가 null 이면 JSON null 로, recentMessages 가 null 이면 빈 배열로 실린다")
    void nullPassthroughFields() {
        stubPlan();
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("26", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of());
        given(aiChatbotClient.call(any(), any())).willReturn(objectMapper.createObjectNode());

        chatbotService.chat(PLAN_ID, USER_ID, request(null, null, null));

        ObjectNode body = capturedBody();
        assertThat(body.get("pendingContext").isNull()).isTrue();
        assertThat(body.get("shownPlaces").isNull()).isTrue();
        assertThat(body.get("recentMessages").isArray()).isTrue();
        assertThat(body.get("recentMessages")).isEmpty();
    }

    // ---------- 실패 전파 ----------

    @Test
    @DisplayName("getCompletePlan 이 던진 예외(권한 없음 등)는 그대로 전파된다")
    void chat_propagatesPlanServiceException() {
        given(planService.getCompletePlan(USER_ID, PLAN_ID)).willThrow(new PlanAccessDeniedException());

        assertThatThrownBy(() -> chatbotService.chat(PLAN_ID, USER_ID, request(null, null, null)))
                .isInstanceOf(PlanAccessDeniedException.class);
    }

    @Test
    @DisplayName("AiChatbotClient 가 던진 예외(AI 불가 등)는 그대로 전파된다")
    void chat_propagatesClientException() {
        stubPlan();
        given(destinationRegionCodeCache.get(DESTINATION_ID)).willReturn(List.of(regionCode("26", null)));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of());
        given(aiChatbotClient.call(any(), any())).willThrow(new ChatbotAiUnavailableException());

        assertThatThrownBy(() -> chatbotService.chat(PLAN_ID, USER_ID, request(null, null, null)))
                .isInstanceOf(ChatbotAiUnavailableException.class);
    }
}
