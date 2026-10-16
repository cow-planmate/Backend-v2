package com.planmate.domain.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planmate.domain.chatbot.client.AiChatbotClient;
import com.planmate.domain.chatbot.dto.ChatbotTurnRequest;
import com.planmate.domain.chatbot.dto.PreferredThemeDto;
import com.planmate.domain.chatbot.dto.RegionCodeDto;
import com.planmate.domain.chatbot.dto.UserContextDto;
import com.planmate.domain.destination.cache.DestinationRegionCodeCache;
import com.planmate.domain.destination.cache.RegionIndexCache;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.chatbot.support.ChatbotBaselineStore;
import com.planmate.domain.chatbot.support.PlanContentFingerprint;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.service.PlanService;
import com.planmate.domain.theme.cache.PreferredThemeLclsSystmCache;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 챗봇 대화 턴을 planmate-ai 로 중계한다.
 *
 * <p>흐름(설계 §1): 프론트가 준 것({@code message}, {@code pendingContext}, {@code shownPlaces},
 * {@code recentMessages})에 v2 가 {@code planId} · {@code planContext}(저장본) · {@code userContext}(앱 DB 파생)
 * 를 채워 planmate-ai 로 보내고, 응답 JSON 을 그대로 반환한다. v2 는 이 경로에서 DB 를 쓰지 않는다.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다(표준 §7): 쓰기가 없고, {@code getCompletePlan} 은 자체
 * {@code readOnly} 트랜잭션을 가지며, {@code findByUserUserId} 는 {@code @EntityGraph} 로 즉시 로딩되므로
 * lazy 문제가 없다. 느린 planmate-ai 호출(2~20초)이 DB 커넥션을 물지 않도록 트랜잭션 밖에 둔다.
 *
 * <p>설계: project_ai/04_기능/ai/06_챗봇_연동_설계.md.
 */
@Service
@RequiredArgsConstructor
public class ChatbotService {

    private final PlanService planService;
    private final DestinationRegionCodeCache destinationRegionCodeCache;
    private final PreferredThemeLclsSystmCache preferredThemeLclsSystmCache;
    private final UserPreferredThemeRepository userPreferredThemeRepository;
    private final AiChatbotClient aiChatbotClient;
    private final ObjectMapper objectMapper;
    private final ChatbotBaselineStore chatbotBaselineStore;
    private final RegionIndexCache regionIndexCache;

    @PreAuthorize("@planAccessValidator.validateMember(#planId, authentication.principal)")
    public JsonNode chat(UUID planId, UUID userId, ChatbotTurnRequest request) {
        GetCompletePlanResponse planContext = planService.getCompletePlan(userId, planId);

        // [반영](④-2) 충돌 감지용 baseline. 이 턴이 마지막 챗 턴일 수 있으므로 매 턴 덮어쓴다
        // (07_반영_reconcile_설계.md §10-1-1 T2).
        chatbotBaselineStore.save(planId, userId, PlanContentFingerprint.of(objectMapper, planContext));

        UserContextDto userContext = assembleUserContext(userId, planContext.planFrame().destinationId());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("planId", planId.toString());
        body.put("message", request.message());
        body.set("planContext", objectMapper.valueToTree(planContext));
        body.set("pendingContext", request.pendingContext());   // null → NullNode
        body.set("shownPlaces", request.shownPlaces());          // null → NullNode
        body.set("userContext", objectMapper.valueToTree(userContext));
        body.set("recentMessages", objectMapper.valueToTree(
                request.recentMessages() != null ? request.recentMessages() : List.of()));

        return aiChatbotClient.call(body, planId);
    }

    /**
     * 앱 DB 에서 {@code userContext} 조립 (설계 §4-2). 추천장소와 달리 카테고리 필터를 하지 않는다
     * — 일정 전체가 다중 카테고리라, 사용자의 선호 테마를 전량 평탄화해 보낸다.
     */
    private UserContextDto assembleUserContext(UUID userId, Integer destinationId) {
        List<DestinationRegionCode> regionCodeEntities = destinationRegionCodeCache.get(destinationId);

        List<RegionCodeDto> regionCodes = regionCodeEntities.stream()
                .map(rc -> new RegionCodeDto(rc.getLdongRegnCd(), rc.getLdongSignguCd()))
                .toList();

        List<String> signguCodes = regionCodeEntities.stream()
                .map(DestinationRegionCode::getLdongSignguCd)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<PreferredThemeDto> preferredThemes = userPreferredThemeRepository.findByUserUserId(userId).stream()
                .flatMap(upt -> preferredThemeLclsSystmCache
                        .get(upt.getPreferredTheme().getPreferredThemeId()).stream())
                .map(code -> new PreferredThemeDto(code.getLclsSystmLevel(), code.getLclsSystmCd()))
                .distinct()
                .toList();

        return new UserContextDto(preferredThemes, regionCodes, signguCodes, regionIndexCache.version());
    }
}
