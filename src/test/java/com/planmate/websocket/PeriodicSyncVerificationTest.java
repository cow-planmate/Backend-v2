package com.planmate.websocket;

import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.timetable.entity.TimeTable;
import com.planmate.domain.timetable.entity.TimeTablePlaceBlock;
import com.planmate.domain.timetable.repository.TimeTablePlaceBlockRepository;
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
import sharedsync.cache.TimeTablePlaceBlockCache;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PeriodicSyncScheduler(30초 주기 Redis→DB flush)가 실제로 동작하는지 실측 검증.
 * 실제 STOMP WebSocket 연결로 블록을 생성해 Redis 캐시에만 값이 들어간 상태를 만들고,
 * PostgreSQL을 순수 JPA 리포지토리(SharedSync 캐시를 거치지 않는 경로)로 직접 폴링하여
 * 몇 초 후에 DB에 실제로 반영되는지를 관찰한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeriodicSyncVerificationTest {

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
    @Autowired
    private TimeTablePlaceBlockRepository timeTablePlaceBlockRepository;
    @Autowired
    private TimeTablePlaceBlockCache timeTablePlaceBlockCache;

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
                .email("periodicsync-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("ps-o-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());

        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();
        plan = planRepository.saveAndFlush(Plan.builder()
                .planName("PeriodicSync 검증 플랜")
                .adultCount(2)
                .childCount(0)
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
        timeTablePlaceBlockRepository.findByTimeTableTimeTableIdOrderByBlockStartTime(timeTable.getTimeTableId())
                .forEach(timeTablePlaceBlockRepository::delete);
        timeTableRepository.delete(timeTable);
        planMemberRepository.deleteAll(planMemberRepository.findAll().stream()
                .filter(m -> m.getPlan().getPlanId().equals(plan.getPlanId())).toList());
        planRepository.delete(plan);
        userRepository.delete(owner);
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
    @DisplayName("실제 WS로 블록 생성 → Redis 캐시 즉시 반영 확인 → PeriodicSyncScheduler가 일정 시간 내에 실제 PostgreSQL에 반영하는지 실측")
    void periodicSyncEventuallyPersistsCacheOnlyDataToRealDatabase() throws Exception {
        StompSession session = connect(ownerToken);
        BlockingQueue<Map<String, Object>> editQueue = subscribeToMap(session, "/topic/" + plan.getPlanId());
        subscribeToMap(session, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500); // cacheInitializer.initializeHierarchy 완료 대기

        String placeName = "PeriodicSyncCheck-" + UUID.randomUUID();

        Map<String, Object> blockDto = new LinkedHashMap<>();
        blockDto.put("blockId", null);
        blockDto.put("timeTableId", timeTable.getTimeTableId());
        blockDto.put("placeId", "periodic-sync-check");
        blockDto.put("placeName", placeName);
        blockDto.put("placeAddress", "서울 어딘가");
        blockDto.put("blockStartTime", "10:00:00");
        blockDto.put("blockEndTime", "11:00:00");
        blockDto.put("blockCategory", "ATTRACTION");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", "periodic-sync-check-" + UUID.randomUUID());
        request.put("action", "create");
        request.put("entity", "timetableplaceblock");
        request.put("timeTablePlaceBlockDtos", List.of(blockDto));

        long sentAt = System.currentTimeMillis();
        session.send("/app/" + plan.getPlanId(), request);

        Map<String, Object> received = pollUntilBlockCreated(editQueue, placeName);
        assertThat(received).as("블록 생성 브로드캐스트를 받지 못함 (WS/Redis 경로 자체가 실패)").isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) received.get("timeTablePlaceBlockDtos");
        Long blockId = ((Number) blocks.get(0).get("blockId")).longValue();
        System.out.println("[VERIFY] WS broadcast confirmed. blockId=" + blockId
                + " elapsedSinceSend=" + (System.currentTimeMillis() - sentAt) + "ms");

        // 1) Redis 캐시(SharedSync 경로)에는 즉시 존재해야 함
        Object cachedDto = timeTablePlaceBlockCache.findDtoById(blockId);
        System.out.println("[VERIFY] Redis cache lookup right after creation: " + (cachedDto != null ? "PRESENT" : "MISSING"));
        assertThat(cachedDto).as("생성 직후 Redis 캐시에 값이 있어야 한다").isNotNull();

        // 2) 순수 JPA 리포지토리(SharedSync 캐시를 거치지 않는 실제 PostgreSQL 직접 조회)로 반영 시점을 폴링
        long deadline = System.currentTimeMillis() + 40_000;
        boolean foundInDb = false;
        long firstSeenAt = -1;
        Optional<TimeTablePlaceBlock> dbRow = Optional.empty();
        while (System.currentTimeMillis() < deadline) {
            dbRow = timeTablePlaceBlockRepository.findById(blockId);
            long elapsed = System.currentTimeMillis() - sentAt;
            if (dbRow.isPresent()) {
                foundInDb = true;
                firstSeenAt = elapsed;
                break;
            }
            System.out.println("[VERIFY] t+" + (elapsed / 1000) + "s: still NOT in PostgreSQL (Redis-only)");
            Thread.sleep(2000);
        }

        if (foundInDb) {
            System.out.println("[VERIFY] FOUND in PostgreSQL at t+" + (firstSeenAt / 1000)
                    + "s. placeName=" + dbRow.get().getPlaceName()
                    + " blockId=" + dbRow.get().getBlockId());
        } else {
            System.out.println("[VERIFY] NOT found in PostgreSQL within 40s timeout.");
        }

        assertThat(foundInDb)
                .as("PeriodicSyncScheduler가 40초 내에 Redis 캐시 데이터를 실제 PostgreSQL 테이블에 반영해야 한다")
                .isTrue();
        assertThat(dbRow).isPresent();
        assertThat(dbRow.get().getPlaceName()).isEqualTo(placeName);

        session.disconnect();
    }

    @Test
    @DisplayName("마지막 인원이 나갔을 때(disconnect-sync)도 실제로 PostgreSQL에 반영되는지 실측 — 동일 코드 경로(saveToDatabase)라 동일 버그 재현 여부 확인")
    void disconnectSyncAlsoAttemptsPersistToRealDatabase() throws Exception {
        StompSession session = connect(ownerToken);
        BlockingQueue<Map<String, Object>> editQueue = subscribeToMap(session, "/topic/" + plan.getPlanId());
        subscribeToMap(session, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500);

        String placeName = "DisconnectSyncCheck-" + UUID.randomUUID();

        Map<String, Object> blockDto = new LinkedHashMap<>();
        blockDto.put("blockId", null);
        blockDto.put("timeTableId", timeTable.getTimeTableId());
        blockDto.put("placeId", "disconnect-sync-check");
        blockDto.put("placeName", placeName);
        blockDto.put("placeAddress", "서울 어딘가");
        blockDto.put("blockStartTime", "12:00:00");
        blockDto.put("blockEndTime", "13:00:00");
        blockDto.put("blockCategory", "ATTRACTION");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", "disconnect-sync-check-" + UUID.randomUUID());
        request.put("action", "create");
        request.put("entity", "timetableplaceblock");
        request.put("timeTablePlaceBlockDtos", List.of(blockDto));

        session.send("/app/" + plan.getPlanId(), request);

        Map<String, Object> received = pollUntilBlockCreated(editQueue, placeName);
        assertThat(received).as("블록 생성 브로드캐스트를 받지 못함").isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) received.get("timeTablePlaceBlockDtos");
        Long blockId = ((Number) blocks.get(0).get("blockId")).longValue();
        System.out.println("[VERIFY-DISCONNECT] blockId=" + blockId + " created. Redis cache PRESENT="
                + (timeTablePlaceBlockCache.findDtoById(blockId) != null));

        // 방에 남은 유일한 유저가 연결을 끊음 → hasTracker(rootId)==false → syncToDatabaseIfLocked
        // → presenceProperties.getSyncDelay()(기본 5초) 뒤 executeSyncWithLock → cacheSyncService.syncToDatabase(rootId, keepCache=false)
        long disconnectAt = System.currentTimeMillis();
        session.disconnect();
        System.out.println("[VERIFY-DISCONNECT] Disconnected. Waiting for disconnect-sync (syncDelay + execution)...");

        long deadline = System.currentTimeMillis() + 25_000;
        boolean foundInDb = false;
        Optional<TimeTablePlaceBlock> dbRow = Optional.empty();
        while (System.currentTimeMillis() < deadline) {
            dbRow = timeTablePlaceBlockRepository.findById(blockId);
            long elapsed = System.currentTimeMillis() - disconnectAt;
            if (dbRow.isPresent()) {
                foundInDb = true;
                System.out.println("[VERIFY-DISCONNECT] FOUND in PostgreSQL at t+" + (elapsed / 1000) + "s");
                break;
            }
            System.out.println("[VERIFY-DISCONNECT] t+" + (elapsed / 1000) + "s: still NOT in PostgreSQL");
            Thread.sleep(2000);
        }

        if (!foundInDb) {
            System.out.println("[VERIFY-DISCONNECT] NOT found in PostgreSQL within 25s of disconnect. "
                    + "Redis cache still PRESENT=" + (timeTablePlaceBlockCache.findDtoById(blockId) != null));
        }

        assertThat(foundInDb)
                .as("마지막 인원 퇴장 시 disconnect-sync가 25초 내에 실제 PostgreSQL에 반영해야 한다")
                .isTrue();
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
                    return msg;
                }
            }
        }
        return null;
    }
}
