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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SharedSync(WebSocket 실시간 협업편집) STOMP 통합 테스트.
 * 01_구현_가이드.md 8-1절의 시나리오 3종을 실제 STOMP 클라이언트로 검증한다.
 *
 * CONNECT 프레임에 connectHeaders로 Authorization을 반드시 실어 보낸다 — WsAuthChannelInterceptor.handleConnect()가
 * HTTP 핸드셰이크 토큰과 별개로 이 네이티브 헤더를 요구한다(00_통합_계획.md 3-11절, 실제 프론트엔 빠져있는 것으로 확인된 부분).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SharedSyncStompIntegrationTest {

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
    private User editor;
    private User editor2;
    private User outsider;
    private String ownerToken;
    private String editorToken;
    private String editor2Token;
    private String outsiderToken;
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
                .email("stomp-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("st-o-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        editor = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("stomp-editor-" + UUID.randomUUID() + "@example.com")
                .nickname("st-e-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        editor2 = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("stomp-editor2-" + UUID.randomUUID() + "@example.com")
                .nickname("st-e2-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        outsider = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("stomp-outsider-" + UUID.randomUUID() + "@example.com")
                .nickname("st-x-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());
        editorToken = jwtTokenProvider.createAccessToken(editor.getUserId(), editor.getRole().name());
        editor2Token = jwtTokenProvider.createAccessToken(editor2.getUserId(), editor2.getRole().name());
        outsiderToken = jwtTokenProvider.createAccessToken(outsider.getUserId(), outsider.getRole().name());

        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();
        plan = planRepository.saveAndFlush(Plan.builder()
                .planName("STOMP 통합테스트 플랜")
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
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(editor)
                .role(PlanMemberRole.EDITOR)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(editor2)
                .role(PlanMemberRole.EDITOR)
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
        userRepository.delete(editor);
        userRepository.delete(editor2);
        userRepository.delete(outsider);
    }

    private StompSession connect(String token) throws Exception {
        String url = "ws://localhost:" + port + "/ws?token=" + token;
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient.connectAsync(url, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
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

    @Test
    @DisplayName("멤버가 연결 → presence/edit 채널 구독(캐시 초기화 트리거) → update 발행 → 같은 토픽으로 브로드캐스트 수신")
    void memberUpdateIsBroadcastToTopic() throws Exception {
        StompSession session = connect(ownerToken);

        BlockingQueue<Map<String, Object>> editQueue = subscribeToMap(session, "/topic/" + plan.getPlanId());
        // presence 채널 구독이 PresenceSessionManager.handleSubscribe()를 트리거해 캐시를 DB에서 초기화한다
        // (SharedEventTracker.handleSubscribeEvent — "/topic/plan-presence/..."만 매칭, 편집 채널 구독은 트리거 안 함).
        subscribeToMap(session, "/topic/plan-presence/" + plan.getPlanId());

        // 캐시 초기화(cacheInitializer.initializeHierarchy)가 끝날 시간을 확보
        Thread.sleep(1500);

        String updatedName = "STOMP로 수정된 이름 " + UUID.randomUUID();
        sendPlanUpdate(session, updatedName);

        Map<String, Object> received = pollUntilPlanUpdate(editQueue, updatedName);
        assertThat(received).isNotNull();
        assertThat(received.get("entity")).isEqualTo("plan");
        assertThat(received.get("action")).isEqualTo("update");

        session.disconnect();
    }

    @Test
    @DisplayName("실제 서로 다른 두 사용자가 동시 접속 — A가 수정하면 B가 실시간으로 받고, B가 수정하면 A가 실시간으로 받는다(양방향 협업편집 실측)")
    void twoDifferentUsersSeeEachOthersEditsInRealTime() throws Exception {
        // owner와 editor는 서로 다른 실제 User 행 + 서로 다른 JWT + 서로 다른 STOMP 세션(별개의 WebSocket 연결)이다.
        // 같은 세션이 자기가 보낸 걸 자기가 받는 게 아니라, A의 세션이 보낸 메시지가 B의 별개 세션 큐에 도착하는지를 검증한다.
        StompSession ownerSession = connect(ownerToken);
        StompSession editorSession = connect(editorToken);

        BlockingQueue<Map<String, Object>> ownerQueue = subscribeToMap(ownerSession, "/topic/" + plan.getPlanId());
        BlockingQueue<Map<String, Object>> editorQueue = subscribeToMap(editorSession, "/topic/" + plan.getPlanId());
        // 둘 중 하나만 presence를 구독해도 첫 입장자가 캐시 초기화를 트리거한다.
        subscribeToMap(ownerSession, "/topic/plan-presence/" + plan.getPlanId());
        subscribeToMap(editorSession, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500);

        // 1) owner(A)가 수정 → editor(B)의 별도 세션이 실시간으로 받는지 확인
        String nameFromOwner = "A가 수정함 " + UUID.randomUUID();
        sendPlanUpdate(ownerSession, nameFromOwner);
        Map<String, Object> editorReceived = pollUntilPlanUpdate(editorQueue, nameFromOwner);
        assertThat(editorReceived)
                .as("owner가 보낸 수정을 editor의 별도 세션이 실시간으로 받아야 한다")
                .isNotNull();

        // 2) editor(B)가 수정 → owner(A)의 별도 세션이 실시간으로 받는지 확인 (반대 방향도 동작 — 진짜 동시 편집)
        String nameFromEditor = "B가 수정함 " + UUID.randomUUID();
        sendPlanUpdate(editorSession, nameFromEditor);
        Map<String, Object> ownerReceived = pollUntilPlanUpdate(ownerQueue, nameFromEditor);
        assertThat(ownerReceived)
                .as("editor가 보낸 수정을 owner의 별도 세션이 실시간으로 받아야 한다")
                .isNotNull();

        ownerSession.disconnect();
        editorSession.disconnect();
    }

    @Test
    @DisplayName("실제 세 사용자가 동시 접속 — 한 명이 수정하면 나머지 두 명 모두에게 실시간으로 fan-out된다")
    void threeDifferentUsersAllReceiveBroadcast() throws Exception {
        // owner/editor/editor2 세 명 전부 서로 다른 실제 User + 서로 다른 JWT + 서로 다른 STOMP 세션(별개 WebSocket 연결).
        // 2인 시나리오는 "상대방 한 명"까지만 확인했는데, 실제 방에는 3명 이상 동시 접속하는 게 정상 사용 패턴이라
        // 브로드캐스트가 "특정 한 명"이 아니라 구독 중인 전원에게 fan-out되는지를 여기서 확인한다.
        StompSession ownerSession = connect(ownerToken);
        StompSession editorSession = connect(editorToken);
        StompSession editor2Session = connect(editor2Token);

        BlockingQueue<Map<String, Object>> ownerQueue = subscribeToMap(ownerSession, "/topic/" + plan.getPlanId());
        BlockingQueue<Map<String, Object>> editorQueue = subscribeToMap(editorSession, "/topic/" + plan.getPlanId());
        BlockingQueue<Map<String, Object>> editor2Queue = subscribeToMap(editor2Session, "/topic/" + plan.getPlanId());
        subscribeToMap(ownerSession, "/topic/plan-presence/" + plan.getPlanId());
        subscribeToMap(editorSession, "/topic/plan-presence/" + plan.getPlanId());
        subscribeToMap(editor2Session, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500);

        // editor(가운데 사용자)가 수정 → owner와 editor2 "둘 다" 각자의 별도 세션으로 실시간 수신해야 한다.
        String newName = "세 명 동시접속 테스트 " + UUID.randomUUID();
        sendPlanUpdate(editorSession, newName);

        Map<String, Object> ownerReceived = pollUntilPlanUpdate(ownerQueue, newName);
        Map<String, Object> editor2Received = pollUntilPlanUpdate(editor2Queue, newName);

        assertThat(ownerReceived).as("editor의 수정을 owner도 실시간으로 받아야 한다").isNotNull();
        assertThat(editor2Received).as("editor의 수정을 editor2도 실시간으로 받아야 한다").isNotNull();

        ownerSession.disconnect();
        editorSession.disconnect();
        editor2Session.disconnect();
    }

    private void sendPlanUpdate(StompSession session, String newPlanName) {
        Map<String, Object> planDto = new LinkedHashMap<>();
        planDto.put("planId", plan.getPlanId().toString());
        planDto.put("planName", newPlanName);
        planDto.put("adultCount", plan.getAdultCount());
        planDto.put("childCount", plan.getChildCount());
        planDto.put("transportationType", plan.getTransportationType().name());
        planDto.put("isShared", plan.isShared());
        planDto.put("userId", owner.getUserId().toString());
        planDto.put("destinationId", plan.getDestination().getDestinationId());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", "test-event-" + UUID.randomUUID());
        request.put("action", "update");
        request.put("entity", "plan");
        request.put("planDtos", List.of(planDto));

        session.send("/app/" + plan.getPlanId(), request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntilPlanUpdate(BlockingQueue<Map<String, Object>> queue, String expectedName)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> msg = queue.poll(1, TimeUnit.SECONDS);
            if (msg == null) continue;
            Object plans = msg.get("planDtos");
            if (plans instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m && expectedName.equals(m.get("planName"))) {
                    return msg;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("비멤버가 같은 플랜 room을 구독하면 PlanRoomAccessValidator가 거부하고 세션이 종료된다")
    void nonMemberSubscribeIsRejected() throws Exception {
        StompSession session = connect(outsiderToken);
        AtomicBoolean exceptionSeen = new AtomicBoolean(false);

        session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws?token=" + outsiderToken,
                new WebSocketHttpHeaders(),
                headersWithAuth(outsiderToken),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession s, org.springframework.messaging.simp.stomp.StompCommand command,
                                                 StompHeaders headers, byte[] payload, Throwable exception) {
                        exceptionSeen.set(true);
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable exception) {
                        exceptionSeen.set(true);
                    }
                }
        ).get(5, TimeUnit.SECONDS);

        session.subscribe("/topic/" + plan.getPlanId(), new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // 정상적으로는 도달하면 안 됨(비멤버라 거부돼야 함)
            }
        });

        // 서버가 AccessDeniedException을 던지면 STOMP ERROR 프레임 + 세션 종료로 이어진다(00_통합_계획.md 4-5절 항목 2 실측)
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && session.isConnected() && !exceptionSeen.get()) {
            Thread.sleep(100);
        }

        assertThat(session.isConnected() && !exceptionSeen.get())
                .as("비멤버 구독은 거부되어야 한다 — 세션이 끊기거나 예외 프레임을 받아야 함")
                .isFalse();
    }

    private StompHeaders headersWithAuth(String token) {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return headers;
    }

    @Test
    @DisplayName("신규 블록 생성(blockId=null) → IdPool이 실제 Long ID를 즉시 배정해 브로드캐스트로 돌아온다")
    void blockCreateAssignsRealLongId() throws Exception {
        StompSession session = connect(ownerToken);
        BlockingQueue<Map<String, Object>> editQueue = subscribeToMap(session, "/topic/" + plan.getPlanId());
        subscribeToMap(session, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500);

        Map<String, Object> blockDto = new LinkedHashMap<>();
        blockDto.put("blockId", null);
        blockDto.put("timeTableId", timeTable.getTimeTableId());
        blockDto.put("placeId", "test-place-1");
        blockDto.put("placeName", "STOMP 테스트 장소");
        blockDto.put("placeAddress", "서울 어딘가");
        blockDto.put("blockStartTime", "10:00:00");
        blockDto.put("blockEndTime", "11:00:00");
        blockDto.put("blockCategory", "ATTRACTION");

        String eventId = "test-block-" + UUID.randomUUID();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", eventId);
        request.put("action", "create");
        request.put("entity", "timetableplaceblock");
        request.put("timeTablePlaceBlockDtos", List.of(blockDto));

        session.send("/app/" + plan.getPlanId(), request);

        Map<String, Object> received = pollUntilBlockCreated(editQueue, "STOMP 테스트 장소");
        assertThat(received).as("블록 생성 브로드캐스트를 받지 못함").isNotNull();

        session.disconnect();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntilBlockCreated(BlockingQueue<Map<String, Object>> queue, String placeName)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> msg = queue.poll(1, TimeUnit.SECONDS);
            if (msg == null) continue;
            Object blocks = msg.get("timeTablePlaceBlockDtos");
            if (blocks instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m && placeName.equals(m.get("placeName"))) {
                    assertThat(m.get("blockId")).as("생성된 블록의 blockId는 null이 아닌 실제 Long이어야 함").isNotNull();
                    return msg;
                }
            }
        }
        return null;
    }
}
