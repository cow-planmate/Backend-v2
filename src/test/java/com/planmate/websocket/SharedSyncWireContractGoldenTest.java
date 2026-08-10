package com.planmate.websocket;

import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.timetable.entity.TimeTable;
import com.planmate.domain.timetable.repository.TimeTableRepository;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 현행 STOMP+JSON wire 계약의 golden 특성화 테스트.
 *
 * SharedSyncStompIntegrationTest/PeriodicSyncVerificationTest가 이미 커버하는 fan-out·접근제어·
 * ID 배정과 겹치지 않고, 계획서(sharedsync-stomp-parallel-wombat.md)의 두 핵심 제약만 잠근다:
 *
 *  - 제약 1 (null-skip 부분 병합): update 로 일부 필드만 보내면 나머지 필드가 보존되는지.
 *    protobuf 전환 시 optional 을 빠뜨리면 이 테스트가 가장 먼저, 가장 크게 깨진다.
 *  - undo/redo 이중 shape: HistoryService.publishChange() 가 entityName.toLowerCase()+"s" 로
 *    만드는 동적 키(예: "timetableplaceblocks")가 일반 편집 응답의 "timeTablePlaceBlockDtos"와
 *    다르다는 사실. transport/codec SPI 리팩터링(2~3단계)과 이후 SyncEvent 통합(§C) 전후로
 *    반드시 재실행해 회귀를 잡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SharedSyncWireContractGoldenTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private DestinationRepository destinationRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanMemberRepository planMemberRepository;
    @Autowired
    private TimeTableRepository timeTableRepository;

    private User owner;
    private String ownerToken;
    private Plan plan;
    private TimeTable timeTable;
    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("wire-golden-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("wg-o-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());

        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();
        plan = planRepository.saveAndFlush(Plan.builder()
                .planName("Wire Golden 테스트 플랜")
                .adultCount(2)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(false)
                .user(owner)
                .destination(destination)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(owner)
                .role(PlanMemberRole.OWNER)
                .build());
        timeTable = timeTableRepository.saveAndFlush(TimeTable.builder()
                .plan(plan)
                .date(LocalDate.now())
                .timeTableStartTime(LocalTime.of(9, 0))
                .timeTableEndTime(LocalTime.of(21, 0))
                .build());
    }

    @AfterEach
    void tearDown() {
        timeTableRepository.delete(timeTable);
        planMemberRepository.deleteAll(planMemberRepository.findAll().stream()
                .filter(m -> m.getPlan().getPlanId().equals(plan.getPlanId())).toList());
        planRepository.delete(plan);
        userRepository.delete(owner);
    }

    private StompSession connect(String token) throws Exception {
        String url = "ws://localhost:" + port + "/ws?token=" + token;
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient.connectAsync(url, new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribeToMap(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<String, Object>) payload);
            }
        });
        return queue;
    }

    private Map<String, Object> fullBlockDto(String placeName) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("blockId", null);
        dto.put("timeTableId", timeTable.getTimeTableId());
        dto.put("placeId", "wire-golden-place");
        dto.put("placeName", placeName);
        dto.put("placeAddress", "서울 어딘가 123");
        dto.put("latitude", "37.5665123");
        dto.put("longitude", "126.9779692");
        dto.put("blockStartTime", "10:00:00");
        dto.put("blockEndTime", "11:00:00");
        dto.put("blockCategory", "ATTRACTION");
        dto.put("memo", "원래 메모");
        return dto;
    }

    private Map<String, Object> createRequest(String eventId, String action, String entity,
                                                String dtosKey, List<Map<String, Object>> dtos) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", eventId);
        request.put("action", action);
        request.put("entity", entity);
        request.put(dtosKey, dtos);
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntil(BlockingQueue<Map<String, Object>> queue, String listKey,
                                            java.util.function.Predicate<Map<String, Object>> matches) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> msg = queue.poll(1, TimeUnit.SECONDS);
            if (msg == null) continue;
            Object list = msg.get(listKey);
            if (list instanceof List<?> l && !l.isEmpty()) {
                Object first = l.get(0);
                if (first instanceof Map<?, ?> m && matches.test((Map<String, Object>) m)) {
                    return msg;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("GOLDEN 제약1: memo만 담은 update는 나머지 필드를 그대로 보존한다 (null-skip 부분 병합)")
    void partialUpdateWithOnlyMemoPreservesOtherFields() throws Exception {
        StompSession session = connect(ownerToken);
        BlockingQueue<Map<String, Object>> editQueue = subscribeToMap(session, "/topic/" + plan.getPlanId());
        subscribeToMap(session, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500);

        String placeName = "GoldenPartialUpdate-" + UUID.randomUUID();
        Map<String, Object> createDto = fullBlockDto(placeName);
        session.send("/app/" + plan.getPlanId(),
                createRequest("golden-create-" + UUID.randomUUID(), "create", "timetableplaceblock",
                        "timeTablePlaceBlockDtos", List.of(createDto)));

        Map<String, Object> created = pollUntil(editQueue, "timeTablePlaceBlockDtos",
                m -> placeName.equals(m.get("placeName")));
        assertThat(created).as("생성 브로드캐스트를 받지 못함").isNotNull();
        @SuppressWarnings("unchecked")
        Long blockId = ((Number) ((List<Map<String, Object>>) created.get("timeTablePlaceBlockDtos"))
                .get(0).get("blockId")).longValue();

        // memo 하나만 담은 부분 업데이트 — 나머지 필드는 요청에 아예 없다
        Map<String, Object> partialDto = new LinkedHashMap<>();
        partialDto.put("blockId", blockId);
        partialDto.put("memo", "수정된 메모");
        session.send("/app/" + plan.getPlanId(),
                createRequest("golden-update-" + UUID.randomUUID(), "update", "timetableplaceblock",
                        "timeTablePlaceBlockDtos", List.of(partialDto)));

        Map<String, Object> updated = pollUntil(editQueue, "timeTablePlaceBlockDtos",
                m -> blockId.equals(((Number) m.get("blockId")).longValue())
                        && "수정된 메모".equals(m.get("memo")));
        assertThat(updated).as("부분 업데이트 브로드캐스트를 받지 못함").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> block = ((List<Map<String, Object>>) updated.get("timeTablePlaceBlockDtos")).get(0);
        assertThat(block.get("memo")).isEqualTo("수정된 메모");
        assertThat(block.get("placeName"))
                .as("update에 없던 placeName이 null-skip 병합으로 보존되어야 한다")
                .isEqualTo(placeName);
        assertThat(block.get("placeAddress"))
                .as("update에 없던 placeAddress가 보존되어야 한다")
                .isEqualTo("서울 어딘가 123");
        assertThat(String.valueOf(block.get("latitude")))
                .as("update에 없던 latitude가 보존되어야 한다")
                .startsWith("37.5665");
        assertThat(block.get("blockCategory"))
                .as("update에 없던 blockCategory가 보존되어야 한다")
                .isEqualTo("ATTRACTION");

        session.disconnect();
    }

    @Test
    @DisplayName("GOLDEN undo: undo 응답도 일반 편집과 같은 키(timeTablePlaceBlockDtos)를 쓰고 isUndoRedo 로만 구분된다")
    void undoResponseUsesSameShapeAsNormalEditDistinguishedOnlyByFlag() throws Exception {
        StompSession session = connect(ownerToken);
        BlockingQueue<Map<String, Object>> editQueue = subscribeToMap(session, "/topic/" + plan.getPlanId());
        subscribeToMap(session, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500);

        String placeName = "GoldenUndo-" + UUID.randomUUID();
        session.send("/app/" + plan.getPlanId(),
                createRequest("golden-undo-create-" + UUID.randomUUID(), "create", "timetableplaceblock",
                        "timeTablePlaceBlockDtos", List.of(fullBlockDto(placeName))));

        Map<String, Object> created = pollUntil(editQueue, "timeTablePlaceBlockDtos",
                m -> placeName.equals(m.get("placeName")));
        assertThat(created).as("생성 브로드캐스트를 받지 못함").isNotNull();
        assertThat(created).as("일반 편집 응답은 엔티티별 정확한 필드명을 쓴다").containsKey("timeTablePlaceBlockDtos");

        // undo 요청 — entity 필드가 아예 없다 (action만 있음)
        Map<String, Object> undoRequest = new LinkedHashMap<>();
        undoRequest.put("action", "undo");
        session.send("/app/" + plan.getPlanId(), undoRequest);

        long deadline = System.currentTimeMillis() + 8000;
        Map<String, Object> undoMsg = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> msg = editQueue.poll(1, TimeUnit.SECONDS);
            if (msg == null) continue;
            if (Boolean.TRUE.equals(msg.get("isUndoRedo"))) {
                undoMsg = msg;
                break;
            }
        }

        assertThat(undoMsg).as("undo 브로드캐스트를 받지 못함").isNotNull();
        assertThat(undoMsg.get("entity")).isEqualTo("timetableplaceblock");
        assertThat(undoMsg.get("action"))
                .as("생성의 undo는 delete로 방송된다 (HistoryService.publishChange 의 CREATE -> DELETE 반전)")
                .isEqualTo("delete");
        assertThat(undoMsg)
                .as("SyncOutbound 도입으로 undo 도 일반 편집과 같은 키를 쓴다. 예전에는 "
                        + "HistoryService.publishChange() 가 entityName.toLowerCase()+\"s\" 로 키를 "
                        + "런타임에 만들어 \"timetableplaceblocks\" 로 나갔고, 프론트가 "
                        + "`body.timeTablePlaceBlockDtos || body.timetableplaceblocks` 로 덮고 있었다. "
                        + "두 클라이언트 모두 그 폴백을 갖고 있으므로 기존 배포본과도 호환된다.")
                .containsKey("timeTablePlaceBlockDtos")
                .doesNotContainKey("timetableplaceblocks");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> undoBlocks =
                (List<Map<String, Object>>) undoMsg.get("timeTablePlaceBlockDtos");
        assertThat(undoBlocks).as("undo 페이로드에 대상 블록이 실려야 한다").isNotEmpty();

        session.disconnect();
    }
}
