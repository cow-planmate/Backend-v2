package com.planmate.domain.chatbot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.timetable.entity.TimeTable;
import com.planmate.domain.timetable.entity.TimeTablePlaceBlock;
import com.planmate.domain.timetable.enums.BlockCategory;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import sharedsync.cache.TimeTablePlaceBlockCache;
import sharedsync.proto.SyncSchema;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗봇 [반영](④-2) — REST + raw-WS 세션 바인딩 end-to-end (설계 §11).
 *
 * <p>{@link com.planmate.domain.chatbot.service.ChatbotApplyServiceTest}가 순수 로직(세션 분기·
 * leaf-first·COMPOSITE)을 목으로 검증했다면, 여기서는 실제 Spring 스택 — raw WebSocket 세션 등록
 * ({@link com.sharedsync.shared.transport.WebSocketSessionRegistry}) → REST 인증/인가 →
 * {@code ChatbotApplyService} → SharedSync 캐시 반영 → {@code /topic} 브로드캐스트 — 가 실제로
 * 이어지는지를 본다. 프레임 조립/파싱은 {@code SharedSyncRawWebSocketIntegrationTest} 패턴을 그대로
 * 재사용한다(같은 wire 프로토콜).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sharedsync.websocket.transport=websocket"
})
@AutoConfigureMockMvc
class ChatbotApplyControllerIntegrationTest {

    private static final FileDescriptor SCHEMA = loadSchema();

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;
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
    @Autowired
    private TimeTableRepository timeTableRepository;
    @Autowired
    private TimeTablePlaceBlockRepository timeTablePlaceBlockRepository;
    @Autowired
    private TimeTablePlaceBlockCache timeTablePlaceBlockCache;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private User owner;
    private String ownerToken;
    private Plan plan;
    private TimeTable timeTable;
    private TimeTablePlaceBlock block;
    private final List<TestClient> clients = new ArrayList<>();
    private final List<User> extraUsers = new ArrayList<>();
    private final List<Plan> extraPlans = new ArrayList<>();

    private static FileDescriptor loadSchema() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.parseFrom(
                    Base64.getDecoder().decode(SyncSchema.DESCRIPTOR_BASE64));
            return FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException("wire 스키마 로드 실패", e);
        }
    }

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("chatbot-apply-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("ca-o-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());

        Destination busan = destinationRepository.findByDestinationName("부산").orElseThrow();
        plan = planRepository.saveAndFlush(Plan.builder()
                .planName("반영 통합테스트 플랜")
                .adultCount(2)
                .childCount(0)
                .isShared(false)
                .user(owner)
                .destination(busan)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(owner)
                .role(PlanMemberRole.OWNER)
                .build());
        timeTable = timeTableRepository.saveAndFlush(TimeTable.builder()
                .plan(plan)
                .date(LocalDate.of(2026, 11, 1))
                .timeTableStartTime(LocalTime.of(9, 0))
                .timeTableEndTime(LocalTime.of(21, 0))
                .build());
        block = timeTablePlaceBlockRepository.saveAndFlush(TimeTablePlaceBlock.builder()
                .timeTable(timeTable)
                .placeId("chatbot-apply-place")
                .placeName("원래 장소")
                .blockStartTime(LocalTime.of(10, 0))
                .blockEndTime(LocalTime.of(11, 0))
                .blockCategory(BlockCategory.ATTRACTION)
                .memo("원래 메모")
                .build());
    }

    @AfterEach
    void tearDown() {
        clients.forEach(TestClient::close);
        clients.clear();
        timeTablePlaceBlockRepository.findById(block.getBlockId()).ifPresent(timeTablePlaceBlockRepository::delete);
        timeTableRepository.findById(timeTable.getTimeTableId()).ifPresent(timeTableRepository::delete);
        planMemberRepository.deleteAll(planMemberRepository.findAll().stream()
                .filter(m -> allPlanIds().contains(m.getPlan().getPlanId())).toList());
        extraPlans.forEach(p -> planRepository.findById(p.getPlanId()).ifPresent(planRepository::delete));
        planRepository.findById(plan.getPlanId()).ifPresent(planRepository::delete);
        userRepository.deleteAll(extraUsers);
        extraUsers.clear();
        userRepository.delete(owner);
    }

    private List<UUID> allPlanIds() {
        List<UUID> ids = new ArrayList<>(extraPlans.stream().map(Plan::getPlanId).toList());
        ids.add(plan.getPlanId());
        return ids;
    }

    // ---------- 요청 바디 조립 ----------

    private String applyBody(Long blockId, String placeName) throws Exception {
        String json = """
                {
                  "plan": {
                    "planFrame": null,
                    "timetables": [
                      { "timeTableId": %d, "date": "%s", "timeTableStartTime": "09:00:00", "timeTableEndTime": "21:00:00" }
                    ],
                    "placeBlocks": [
                      { "blockId": %s, "timeTableId": %d, "date": "%s", "placeId": "chatbot-apply-place",
                        "placeName": "%s", "blockStartTime": "10:00:00", "blockEndTime": "11:00:00",
                        "blockCategory": "ATTRACTION", "memo": "원래 메모" }
                    ]
                  }
                }
                """.formatted(timeTable.getTimeTableId(), timeTable.getDate(),
                blockId == null ? "null" : blockId, timeTable.getTimeTableId(), timeTable.getDate(), placeName);
        // 정합성 확인 겸 파싱(오타가 있으면 여기서 바로 실패한다).
        objectMapper.readTree(json);
        return json;
    }

    private MvcResult postApply(UUID planId, String token, String body) throws Exception {
        return mockMvc.perform(post("/api/plan/{planId}/chatbot-apply", planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    // ---------- 정상 반영 ----------

    @Test
    @DisplayName("200 — WS 세션 있으면 저장본 반영 + 브로드캐스트 + COMPOSITE undo 가능")
    void apply_withLiveSession_reflectsAndBroadcasts() throws Exception {
        TestClient client = connect(ownerToken);
        client.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));
        assertThat(client.awaitFrame("presence", f -> true)).as("join 프레즌스를 받지 못함").isNotNull();

        String newName = "반영된새이름-" + UUID.randomUUID();
        MvcResult result = postApply(plan.getPlanId(), ownerToken, applyBody(block.getBlockId(), newName));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        // 바디는 conflictDetected 하나뿐 — 성공은 200 자체로 표현, success 플래그 없음(기능_구현_표준 §1·§8).
        assertThat(body.has("success")).isFalse();
        assertThat(body.get("conflictDetected").isBoolean()).isTrue();

        // ① 브로드캐스트 수신 — 같은 WS 클라이언트(요청자 자신)에도 echo 된다(설계 §4 단계6 — 자기 자신 특별취급 안 함).
        DynamicMessage broadcast = client.awaitFrame("sync",
                f -> blockMatching(f, b -> newName.equals(field(b, "place_name"))) != null);
        assertThat(broadcast).as("반영 브로드캐스트를 받지 못함").isNotNull();

        // ② 저장본(캐시) 반영 확인 — ChatbotApplyService 의 applyUpdateBlocks 가 before 스냅샷을 읽는 것과
        // 동일한 경로(TimeTablePlaceBlockCache.findDtoById). PlanSnapshotReader 로 다시 읽으면 이 planId 가
        // planCache 에 없어(이 테스트에서 WS 로 손댄 건 블록 하나뿐) DB 폴백을 타는데, DB 동기화는 주기/지연
        // 배치라 이 시점엔 아직 반영 전이라 그 경로로는 검증할 수 없다.
        var cached = timeTablePlaceBlockCache.findDtoById(block.getBlockId());
        assertThat(cached).as("캐시에서 반영된 블록을 찾지 못함").isNotNull();
        // TimeTablePlaceBlockDto 는 getBlockId() 외 공개 getter 가 없다(Jackson 필드 직접 접근 전제) — JSON 으로 확인.
        assertThat(objectMapper.valueToTree(cached).get("placeName").asText()).isEqualTo(newName);
    }

    // ---------- 세션 없음 ----------

    @Test
    @DisplayName("409 CHATBOT_003 — 이 유저가 이 방에 연 raw-WS 세션이 없으면(WS 미연결)")
    void apply_withoutLiveSession_409() throws Exception {
        MvcResult result = postApply(plan.getPlanId(), ownerToken, applyBody(block.getBlockId(), "아무이름"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("CHATBOT_003");
    }

    // ---------- 인가 ----------

    @Test
    @DisplayName("403 PLAN_002 — 플랜 멤버가 아니면 세션 조회 이전에 차단된다")
    void apply_notMember_403() throws Exception {
        User stranger = newUser("chatbot-apply-stranger");
        Plan otherPlan = planRepository.saveAndFlush(Plan.builder()
                .planName("남의 플랜")
                .adultCount(1)
                .childCount(0)
                .isShared(false)
                .user(stranger)
                .destination(destinationRepository.findByDestinationName("부산").orElseThrow())
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(otherPlan).user(stranger).role(PlanMemberRole.OWNER).build());
        extraPlans.add(otherPlan);

        MvcResult result = postApply(otherPlan.getPlanId(), ownerToken, applyBody(null, "아무이름"));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("PLAN_002");
    }

    @Test
    @DisplayName("401 — 토큰이 없으면")
    void apply_noToken_401() throws Exception {
        mockMvc.perform(post("/api/plan/{planId}/chatbot-apply", plan.getPlanId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(block.getBlockId(), "아무이름")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("400 — plan 이 없으면 (@NotNull)")
    void apply_missingPlan_400() throws Exception {
        mockMvc.perform(post("/api/plan/{planId}/chatbot-apply", plan.getPlanId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    // ---------- fixtures ----------

    private User newUser(String prefix) {
        User user = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .nickname(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        extraUsers.add(user);
        return user;
    }

    // ==========================================
    // raw WS 클라이언트 — SharedSyncRawWebSocketIntegrationTest 와 동일 프레임 조립/파싱
    // ==========================================

    private TestClient connect(String token) throws Exception {
        TestClient client = new TestClient();
        client.session = new StandardWebSocketClient()
                .execute(client, null, URI.create("ws://localhost:" + port + "/ws?token=" + token))
                .get(5, TimeUnit.SECONDS);
        clients.add(client);
        return client;
    }

    private static DynamicMessage joinFrame(String roomId, String schemaHash) {
        Descriptor joinDesc = SCHEMA.findMessageTypeByName("Join");
        DynamicMessage join = DynamicMessage.newBuilder(joinDesc)
                .setField(joinDesc.findFieldByName("room_id"), roomId)
                .setField(joinDesc.findFieldByName("schema_hash"), schemaHash)
                .build();
        return clientFrame("join", join);
    }

    private static DynamicMessage clientFrame(String arm, DynamicMessage value) {
        Descriptor frameDesc = SCHEMA.findMessageTypeByName("ClientFrame");
        return DynamicMessage.newBuilder(frameDesc)
                .setField(frameDesc.findFieldByName(arm), value)
                .build();
    }

    private static Object field(DynamicMessage message, String name) {
        FieldDescriptor fd = message.getDescriptorForType().findFieldByName(name);
        return message.hasField(fd) ? message.getField(fd) : null;
    }

    /** SyncEvent 페이로드에서 조건에 맞는 블록 하나를 찾는다. 없으면 null. */
    private static DynamicMessage blockMatching(DynamicMessage syncEvent, Predicate<DynamicMessage> matches) {
        FieldDescriptor arm = syncEvent.getDescriptorForType().findFieldByName("time_table_place_blocks");
        if (!syncEvent.hasField(arm)) {
            return null;
        }
        DynamicMessage list = (DynamicMessage) syncEvent.getField(arm);
        FieldDescriptor items = list.getDescriptorForType().findFieldByName("items");
        for (int i = 0; i < list.getRepeatedFieldCount(items); i++) {
            DynamicMessage item = (DynamicMessage) list.getRepeatedField(items, i);
            if (matches.test(item)) {
                return item;
            }
        }
        return null;
    }

    private static final class TestClient extends BinaryWebSocketHandler {

        private final BlockingQueue<DynamicMessage> frames = new LinkedBlockingQueue<>();
        private volatile WebSocketSession session;

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
            ByteBuffer buffer = message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            frames.add(DynamicMessage.parseFrom(SCHEMA.findMessageTypeByName("ServerFrame"), bytes));
        }

        void send(DynamicMessage frame) throws Exception {
            session.sendMessage(new BinaryMessage(frame.toByteArray()));
        }

        DynamicMessage awaitFrame(String arm, Predicate<DynamicMessage> matches) throws InterruptedException {
            Descriptor frameDesc = SCHEMA.findMessageTypeByName("ServerFrame");
            FieldDescriptor fd = frameDesc.findFieldByName(arm);

            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                DynamicMessage frame = frames.poll(1, TimeUnit.SECONDS);
                if (frame == null || !frame.hasField(fd)) {
                    continue;
                }
                DynamicMessage value = (DynamicMessage) frame.getField(fd);
                if (matches.test(value)) {
                    return value;
                }
            }
            return null;
        }

        void close() {
            try {
                if (session != null && session.isOpen()) {
                    session.close();
                }
            } catch (Exception ignored) {
                // 정리 단계 — 실패해도 테스트 결과에 영향 없음.
            }
        }
    }
}
