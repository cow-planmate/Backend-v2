package com.planmate.websocket;

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
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import sharedsync.proto.SyncSchema;

import java.lang.reflect.Type;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * transport=both — STOMP+JSON 과 raw WebSocket+protobuf 를 한 서버에서 동시에 서비스한다.
 *
 * 이게 없으면 전환은 "모든 클라이언트를 같은 순간에 바꾸기"가 된다. 웹과 앱의 배포 시점이 다르고
 * 앱은 사용자가 업데이트해야 하므로, 실제로는 불가능한 요구다.
 *
 * 핵심은 두 채널이 **같은 룸을 공유**한다는 것이다: 구버전 클라이언트가 만든 편집을 신버전이
 * 받아야 하고 그 반대도 성립해야 한다. 채널마다 인코딩이 다르므로(JSON / protobuf) 같은 논리
 * 메시지를 채널 수만큼 인코딩해 내보낸다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sharedsync.websocket.transport=both"
})
class SharedSyncDualTransportTest {

    private static final FileDescriptor SCHEMA = loadSchema();

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
    private ProtoClient protoClient;

    private static FileDescriptor loadSchema() {
        try {
            return FileDescriptor.buildFrom(FileDescriptorProto.parseFrom(
                    Base64.getDecoder().decode(SyncSchema.DESCRIPTOR_BASE64)), new FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException("wire 스키마 로드 실패", e);
        }
    }

    @BeforeEach
    void setUp() {
        // 구버전 클라이언트와 같은 방식(SockJS)으로 붙는다. both 모드에서 STOMP 엔드포인트는
        // SockJS 를 켠 채로 남으므로, 네이티브 WS 로 /ws 에 직접 붙으면 400 이 된다.
        stompClient = new WebSocketStompClient(new org.springframework.web.socket.sockjs.client.SockJsClient(
                List.of(new org.springframework.web.socket.sockjs.client.WebSocketTransport(
                        new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("dual-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("dual-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());

        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();
        plan = planRepository.saveAndFlush(Plan.builder()
                .planName("Dual transport 테스트 플랜")
                .adultCount(2)
                .childCount(0)
                .isShared(false)
                .user(owner)
                .destination(destination)
                .build());
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan).user(owner).role(PlanMemberRole.OWNER).build());
        timeTable = timeTableRepository.saveAndFlush(TimeTable.builder()
                .plan(plan)
                .date(LocalDate.now())
                .timeTableStartTime(LocalTime.of(9, 0))
                .timeTableEndTime(LocalTime.of(21, 0))
                .build());
    }

    @AfterEach
    void tearDown() {
        if (protoClient != null) {
            protoClient.close();
        }
        timeTableRepository.delete(timeTable);
        planMemberRepository.deleteAll(planMemberRepository.findAll().stream()
                .filter(m -> m.getPlan().getPlanId().equals(plan.getPlanId())).toList());
        planRepository.delete(plan);
        userRepository.delete(owner);
    }

    @Test
    @DisplayName("STOMP 클라이언트의 편집을 raw WebSocket 클라이언트가 받는다 (구버전 -> 신버전)")
    void stompEditReachesProtobufClient() throws Exception {
        protoClient = connectProto();
        protoClient.join(plan.getPlanId().toString());
        assertThat(protoClient.await("presence", f -> true)).as("raw WS 입장 실패").isNotNull();

        StompSession stomp = connectStomp();
        BlockingQueue<Map<String, Object>> stompQueue = subscribe(stomp, "/topic/" + plan.getPlanId());
        subscribe(stomp, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500);

        String placeName = "DualFromStomp-" + UUID.randomUUID();
        stomp.send("/app/" + plan.getPlanId(), createRequest(placeName));

        assertThat(pollBlock(stompQueue, placeName)).as("STOMP 클라이언트가 자기 편집을 못 받았다").isNotNull();

        DynamicMessage received = protoClient.await("sync",
                f -> blockMatching(f, b -> placeName.equals(field(b, "place_name"))) != null);
        assertThat(received)
                .as("같은 룸이므로 STOMP 편집이 protobuf 채널로도 나가야 한다. 안 그러면 전환 기간에 "
                        + "두 클라이언트가 서로의 편집을 보지 못한다.")
                .isNotNull();
    }

    @Test
    @DisplayName("raw WebSocket 클라이언트의 편집을 STOMP 클라이언트가 받는다 (신버전 -> 구버전)")
    void protobufEditReachesStompClient() throws Exception {
        StompSession stomp = connectStomp();
        BlockingQueue<Map<String, Object>> stompQueue = subscribe(stomp, "/topic/" + plan.getPlanId());
        subscribe(stomp, "/topic/plan-presence/" + plan.getPlanId());
        Thread.sleep(1500);

        protoClient = connectProto();
        protoClient.join(plan.getPlanId().toString());
        assertThat(protoClient.await("presence", f -> true)).as("raw WS 입장 실패").isNotNull();

        String placeName = "DualFromProto-" + UUID.randomUUID();
        protoClient.send(editFrame(placeName));

        assertThat(pollBlock(stompQueue, placeName))
                .as("protobuf 편집이 JSON 채널로도 나가야 한다")
                .isNotNull();
    }

    // ==========================================
    // STOMP 쪽
    // ==========================================

    private StompSession connectStomp() throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + ownerToken);
        return stompClient.connectAsync("ws://localhost:" + port + "/ws?token=" + ownerToken,
                new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
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

    private Map<String, Object> createRequest(String placeName) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("blockId", null);
        dto.put("timeTableId", timeTable.getTimeTableId());
        dto.put("placeId", "dual-place");
        dto.put("placeName", placeName);
        dto.put("blockStartTime", "10:00:00");
        dto.put("blockEndTime", "11:00:00");
        dto.put("blockCategory", "ATTRACTION");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", "dual-" + UUID.randomUUID());
        request.put("action", "create");
        request.put("entity", "timetableplaceblock");
        request.put("timeTablePlaceBlockDtos", List.of(dto));
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollBlock(BlockingQueue<Map<String, Object>> queue, String placeName)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> message = queue.poll(1, TimeUnit.SECONDS);
            if (message == null) {
                continue;
            }
            Object list = message.get("timeTablePlaceBlockDtos");
            if (list instanceof List<?> blocks && !blocks.isEmpty()
                    && blocks.get(0) instanceof Map<?, ?> block
                    && placeName.equals(((Map<String, Object>) block).get("placeName"))) {
                return message;
            }
        }
        return null;
    }

    // ==========================================
    // raw WebSocket 쪽
    // ==========================================

    private ProtoClient connectProto() throws Exception {
        ProtoClient client = new ProtoClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of("sharedsync.v1", "bearer." + ownerToken));
        // both 모드에서 raw WS 는 STOMP 와 다른 경로로 뜬다 (기본값 endpoint + "/v2").
        client.session = new StandardWebSocketClient()
                .execute(client, headers, URI.create("ws://localhost:" + port + "/ws/v2"))
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    private static DynamicMessage editFrame(String placeName) {
        Descriptor blockDesc = SCHEMA.findMessageTypeByName("TimeTablePlaceBlock");
        DynamicMessage.Builder block = DynamicMessage.newBuilder(blockDesc);
        block.setField(blockDesc.findFieldByName("place_id"), "dual-place");
        block.setField(blockDesc.findFieldByName("place_name"), placeName);
        block.setField(blockDesc.findFieldByName("block_start_time"), "10:00:00");
        block.setField(blockDesc.findFieldByName("block_end_time"), "11:00:00");
        FieldDescriptor category = blockDesc.findFieldByName("block_category");
        block.setField(category, category.getEnumType().findValueByName("BLOCK_CATEGORY_ATTRACTION"));

        Descriptor listDesc = SCHEMA.findMessageTypeByName("TimeTablePlaceBlockList");
        DynamicMessage list = DynamicMessage.newBuilder(listDesc)
                .addRepeatedField(listDesc.findFieldByName("items"), block.build())
                .build();

        Descriptor requestDesc = SCHEMA.findMessageTypeByName("SyncRequest");
        DynamicMessage request = DynamicMessage.newBuilder(requestDesc)
                .setField(requestDesc.findFieldByName("event_id"), "dual-" + UUID.randomUUID())
                .setField(requestDesc.findFieldByName("action"),
                        requestDesc.findFieldByName("action").getEnumType().findValueByName("SYNC_ACTION_CREATE"))
                .setField(requestDesc.findFieldByName("time_table_place_blocks"), list)
                .build();

        Descriptor frameDesc = SCHEMA.findMessageTypeByName("ClientFrame");
        return DynamicMessage.newBuilder(frameDesc)
                .setField(frameDesc.findFieldByName("sync"), request)
                .build();
    }

    private static Object field(DynamicMessage message, String name) {
        FieldDescriptor fd = message.getDescriptorForType().findFieldByName(name);
        return message.hasField(fd) ? message.getField(fd) : null;
    }

    private static DynamicMessage blockMatching(DynamicMessage syncEvent,
                                                java.util.function.Predicate<DynamicMessage> matches) {
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

    private static final class ProtoClient extends BinaryWebSocketHandler {

        private final BlockingQueue<DynamicMessage> frames = new LinkedBlockingQueue<>();
        private volatile WebSocketSession session;

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
            ByteBuffer buffer = message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            frames.add(DynamicMessage.parseFrom(SCHEMA.findMessageTypeByName("ServerFrame"), bytes));
        }

        void join(String roomId) throws Exception {
            Descriptor joinDesc = SCHEMA.findMessageTypeByName("Join");
            DynamicMessage join = DynamicMessage.newBuilder(joinDesc)
                    .setField(joinDesc.findFieldByName("room_id"), roomId)
                    .setField(joinDesc.findFieldByName("schema_hash"), SyncSchema.SCHEMA_HASH)
                    .build();
            Descriptor frameDesc = SCHEMA.findMessageTypeByName("ClientFrame");
            send(DynamicMessage.newBuilder(frameDesc)
                    .setField(frameDesc.findFieldByName("join"), join)
                    .build());
        }

        void send(DynamicMessage frame) throws Exception {
            session.sendMessage(new BinaryMessage(frame.toByteArray()));
        }

        DynamicMessage await(String arm, java.util.function.Predicate<DynamicMessage> matches)
                throws InterruptedException {
            FieldDescriptor fd = SCHEMA.findMessageTypeByName("ServerFrame").findFieldByName(arm);
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
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception ignored) {
                // 정리 단계
            }
        }
    }
}
