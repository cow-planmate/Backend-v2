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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

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

/**
 * raw WebSocket + protobuf transport 의 end-to-end 테스트.
 *
 * STOMP 경로(SharedSyncWireContractGoldenTest)와 같은 시나리오를 새 transport 로 다시 확인한다.
 * 특히 부분 업데이트는 두 transport 가 서로 다른 이유로 깨질 수 있어서 양쪽 모두에서 잠근다:
 * STOMP+JSON 은 "키가 아예 없음", raw WS+protobuf 는 "optional 필드 미설정"으로 같은 의미를
 * 표현하는데, proto 스키마에서 optional 을 빠뜨리면 여기서만 조용히 빈 값 덮어쓰기가 된다.
 *
 * STOMP 는 이 컨텍스트에서 아예 꺼진다(SharedWebSocketConfig 가 transport=stomp 조건부).
 *
 * 프로퍼티가 transport 하나뿐인 것이 의도다 — codec=protobuf 와 sockjs=false 는
 * SharedSyncWebSocketProperties.normalize() 가 맞춘다. 앱이 프레임워크의 내부 제약을 알고
 * 설정 세 개를 함께 고쳐야 했다면 그건 프레임워크가 덜 만들어진 것이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sharedsync.websocket.transport=websocket"
})
class SharedSyncRawWebSocketIntegrationTest {

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
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private User owner;
    private String ownerToken;
    private Plan plan;
    private TimeTable timeTable;
    private final List<TestClient> clients = new ArrayList<>();
    private final List<User> extraUsers = new ArrayList<>();

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
                .email("raw-ws-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("rw-o-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        ownerToken = jwtTokenProvider.createAccessToken(owner.getUserId(), owner.getRole().name());

        Destination destination = destinationRepository.findByDestinationName("서울").orElseThrow();
        plan = planRepository.saveAndFlush(Plan.builder()
                .planName("Raw WS 테스트 플랜")
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
        clients.forEach(TestClient::close);
        clients.clear();
        timeTableRepository.delete(timeTable);
        planMemberRepository.deleteAll(planMemberRepository.findAll().stream()
                .filter(m -> m.getPlan().getPlanId().equals(plan.getPlanId())).toList());
        planRepository.delete(plan);
        userRepository.deleteAll(extraUsers);
        extraUsers.clear();
        userRepository.delete(owner);
    }

    /** 이 플랜의 멤버가 아닌 사용자. 인가 거부 시나리오용. */
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
    // 테스트
    // ==========================================

    @Test
    @DisplayName("연결하면 서버가 Hello 로 스키마 해시를 먼저 알린다")
    void serverSendsHelloWithSchemaHash() throws Exception {
        TestClient client = connect(ownerToken);

        DynamicMessage hello = client.awaitFrame("hello", f -> true);
        assertThat(hello).as("Hello 프레임을 받지 못함").isNotNull();
        assertThat(field(hello, "schema_hash"))
                .as("클라이언트는 이 값으로 자기 스키마와의 스큐를 판별한다")
                .isEqualTo(SyncSchema.SCHEMA_HASH);
    }

    @Test
    @DisplayName("Join 하면 프레즌스 스냅샷이 오고, 편집은 SyncEvent 로 브로드캐스트된다")
    void joinThenCreateBroadcastsSyncEvent() throws Exception {
        TestClient client = connect(ownerToken);
        client.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));

        DynamicMessage presence = client.awaitFrame("presence", f -> true);
        assertThat(presence).as("Join 후 프레즌스 스냅샷을 받지 못함").isNotNull();

        String placeName = "RawWsCreate-" + UUID.randomUUID();
        client.send(editFrame("raw-create-" + UUID.randomUUID(), "SYNC_ACTION_CREATE",
                block(b -> {
                    setField(b, "time_table_id", timeTable.getTimeTableId());
                    setField(b, "place_id", "raw-ws-place");
                    setField(b, "place_name", placeName);
                    setField(b, "place_address", "서울 어딘가 123");
                    setField(b, "latitude", "37.5665123");
                    setField(b, "longitude", "126.9779692");
                    setField(b, "block_start_time", "10:00:00");
                    setField(b, "block_end_time", "11:00:00");
                    setEnum(b, "block_category", "BLOCK_CATEGORY_ATTRACTION");
                    setField(b, "memo", "원래 메모");
                })));

        DynamicMessage created = client.awaitFrame("sync",
                f -> blockMatching(f, b -> placeName.equals(field(b, "place_name"))) != null);
        assertThat(created).as("생성 브로드캐스트를 받지 못함").isNotNull();

        DynamicMessage block = blockMatching(created, b -> placeName.equals(field(b, "place_name")));
        assertThat(block).isNotNull();
        assertThat(field(block, "block_id")).as("서버가 배정한 ID 가 실려야 한다").isNotNull();
        assertThat(String.valueOf(enumName(block, "block_category"))).isEqualTo("BLOCK_CATEGORY_ATTRACTION");
    }

    @Test
    @DisplayName("memo 만 설정한 update 는 나머지 필드를 보존한다 (proto optional 미설정 = null-skip 부분 병합)")
    void partialUpdatePreservesUnsetFields() throws Exception {
        TestClient client = connect(ownerToken);
        client.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));
        assertThat(client.awaitFrame("presence", f -> true)).isNotNull();

        String placeName = "RawWsPartial-" + UUID.randomUUID();
        client.send(editFrame("raw-create-" + UUID.randomUUID(), "SYNC_ACTION_CREATE",
                block(b -> {
                    setField(b, "time_table_id", timeTable.getTimeTableId());
                    setField(b, "place_id", "raw-ws-place");
                    setField(b, "place_name", placeName);
                    setField(b, "place_address", "서울 어딘가 123");
                    setField(b, "latitude", "37.5665123");
                    setField(b, "longitude", "126.9779692");
                    setField(b, "block_start_time", "10:00:00");
                    setField(b, "block_end_time", "11:00:00");
                    setEnum(b, "block_category", "BLOCK_CATEGORY_ATTRACTION");
                    setField(b, "memo", "원래 메모");
                })));

        DynamicMessage created = client.awaitFrame("sync",
                f -> blockMatching(f, b -> placeName.equals(field(b, "place_name"))) != null);
        assertThat(created).as("생성 브로드캐스트를 받지 못함").isNotNull();
        long blockId = (Long) field(blockMatching(created, b -> placeName.equals(field(b, "place_name"))), "block_id");

        // block_id 와 memo 만 설정한다. 나머지는 미설정(hasField=false)이라 서버에서 병합 대상이 아니다.
        client.send(editFrame("raw-update-" + UUID.randomUUID(), "SYNC_ACTION_UPDATE",
                block(b -> {
                    setField(b, "block_id", blockId);
                    setField(b, "memo", "수정된 메모");
                })));

        DynamicMessage updated = client.awaitFrame("sync",
                f -> blockMatching(f, b -> "수정된 메모".equals(field(b, "memo"))) != null);
        assertThat(updated).as("부분 업데이트 브로드캐스트를 받지 못함").isNotNull();

        DynamicMessage block = blockMatching(updated, b -> "수정된 메모".equals(field(b, "memo")));
        assertThat(field(block, "block_id")).isEqualTo(blockId);
        assertThat(field(block, "place_name"))
                .as("update 에 없던 place_name 이 보존되어야 한다").isEqualTo(placeName);
        assertThat(field(block, "place_address"))
                .as("update 에 없던 place_address 가 보존되어야 한다").isEqualTo("서울 어딘가 123");
        assertThat(String.valueOf(field(block, "latitude")))
                .as("update 에 없던 latitude 가 보존되어야 한다").startsWith("37.5665");
        assertThat(String.valueOf(enumName(block, "block_category")))
                .as("update 에 없던 block_category 가 보존되어야 한다").isEqualTo("BLOCK_CATEGORY_ATTRACTION");
    }

    @Test
    @DisplayName("Join 없이 편집을 보내면 NOT_JOINED 에러가 온다")
    void editBeforeJoinIsRejected() throws Exception {
        TestClient client = connect(ownerToken);
        assertThat(client.awaitFrame("hello", f -> true)).isNotNull();

        client.send(editFrame("raw-orphan-" + UUID.randomUUID(), "SYNC_ACTION_CREATE",
                block(b -> setField(b, "time_table_id", timeTable.getTimeTableId()))));

        DynamicMessage error = client.awaitFrame("error", f -> true);
        assertThat(error).as("에러 프레임을 받지 못함").isNotNull();
        assertThat(field(error, "code")).isEqualTo("NOT_JOINED");
    }

    @Test
    @DisplayName("스키마 해시가 다르면 Join 이 거부된다")
    void joinWithMismatchedSchemaHashIsRejected() throws Exception {
        TestClient client = connect(ownerToken);
        assertThat(client.awaitFrame("hello", f -> true)).isNotNull();

        client.send(joinFrame(plan.getPlanId().toString(), "deadbeefdeadbeef"));

        DynamicMessage error = client.awaitFrame("error", f -> true);
        assertThat(error).as("에러 프레임을 받지 못함").isNotNull();
        assertThat(field(error, "code"))
                .as("스키마가 다르면 필드 번호 해석이 어긋나 조용히 엉뚱한 값이 들어간다. 연결을 끊는 편이 낫다.")
                .isEqualTo("SCHEMA_MISMATCH");
    }

    @Test
    @DisplayName("플랜 멤버가 아니면 Join 이 거부된다 (앱의 기존 StompAccessValidator 를 그대로 재사용)")
    void nonMemberJoinIsRejected() throws Exception {
        User outsider = newUser("raw-ws-outsider");
        String outsiderToken = jwtTokenProvider.createAccessToken(outsider.getUserId(), outsider.getRole().name());

        TestClient client = connect(outsiderToken);
        assertThat(client.awaitFrame("hello", f -> true)).isNotNull();
        client.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));

        DynamicMessage error = client.awaitFrame("error", f -> true);
        assertThat(error)
                .as("StompAccessValidatorAdapter 가 roomId 로 목적지를 되만들어 앱의 PlanRoomAccessValidator 에 "
                        + "넘긴다. 정규식이 어긋나면 아무 validator 도 매칭되지 않아 조용히 통과하므로 "
                        + "이 테스트가 그 실패 모드를 잡는다.")
                .isNotNull();
        assertThat(field(error, "code")).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("undo 는 같은 SyncEvent 모양으로 오고 is_undo_redo 로만 구분된다")
    void undoBroadcastsInvertedActionWithFlag() throws Exception {
        TestClient client = connect(ownerToken);
        client.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));
        assertThat(client.awaitFrame("presence", f -> true)).isNotNull();

        String placeName = "RawWsUndo-" + UUID.randomUUID();
        client.send(editFrame("raw-undo-create-" + UUID.randomUUID(), "SYNC_ACTION_CREATE",
                block(b -> {
                    setField(b, "time_table_id", timeTable.getTimeTableId());
                    setField(b, "place_id", "raw-ws-place");
                    setField(b, "place_name", placeName);
                    setField(b, "block_start_time", "10:00:00");
                    setField(b, "block_end_time", "11:00:00");
                    setEnum(b, "block_category", "BLOCK_CATEGORY_ATTRACTION");
                })));
        assertThat(client.awaitFrame("sync",
                f -> blockMatching(f, b -> placeName.equals(field(b, "place_name"))) != null))
                .as("생성 브로드캐스트를 받지 못함").isNotNull();

        // undo 는 페이로드 없이 action 만 실려 온다.
        client.send(undoFrame());

        DynamicMessage undo = client.awaitFrame("sync",
                f -> Boolean.TRUE.equals(rawField(f, "is_undo_redo")));
        assertThat(undo)
                .as("HistoryService.record 는 세션 ID 가 없으면 예외 없이 기록을 건너뛴다. raw WS 는 "
                        + "SimpAttributes 가 없어 핸들러가 ThreadLocal 을 채워야 하는데, 그게 빠지면 "
                        + "여기서 undo 가 아예 오지 않는다.")
                .isNotNull();
        assertThat(String.valueOf(rawField(undo, "action")))
                .as("생성의 undo 는 delete 로 방송된다").isEqualTo("SYNC_ACTION_DELETE");
        assertThat(blockMatching(undo, b -> true)).as("undo 페이로드에 대상 블록이 실려야 한다").isNotNull();
    }

    @Test
    @DisplayName("같은 룸의 다른 세션에 편집이 전파되고, 연결이 끊기면 퇴장이 방송된다")
    void editFansOutAndDisconnectBroadcastsLeave() throws Exception {
        User editor = newUser("raw-ws-editor");
        planMemberRepository.saveAndFlush(PlanMember.builder()
                .plan(plan)
                .user(editor)
                .role(PlanMemberRole.EDITOR)
                .build());
        String editorToken = jwtTokenProvider.createAccessToken(editor.getUserId(), editor.getRole().name());

        TestClient a = connect(ownerToken);
        a.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));
        assertThat(a.awaitFrame("presence", f -> true)).isNotNull();

        TestClient b = connect(editorToken);
        b.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));
        assertThat(b.awaitFrame("presence", f -> true)).isNotNull();

        String placeName = "RawWsFanout-" + UUID.randomUUID();
        a.send(editFrame("raw-fanout-" + UUID.randomUUID(), "SYNC_ACTION_CREATE",
                block(bb -> {
                    setField(bb, "time_table_id", timeTable.getTimeTableId());
                    setField(bb, "place_id", "raw-ws-place");
                    setField(bb, "place_name", placeName);
                    setField(bb, "block_start_time", "10:00:00");
                    setField(bb, "block_end_time", "11:00:00");
                    setEnum(bb, "block_category", "BLOCK_CATEGORY_ATTRACTION");
                })));

        assertThat(b.awaitFrame("sync",
                f -> blockMatching(f, blk -> placeName.equals(field(blk, "place_name"))) != null))
                .as("다른 세션이 편집 브로드캐스트를 받지 못함").isNotNull();

        // A 가 나가면 B 는 퇴장 프레즌스를 받는다. STOMP 의 SessionDisconnectEvent 자리를
        // afterConnectionClosed 가 대신한다.
        a.close();
        DynamicMessage leave = b.awaitFrame("presence",
                f -> "SYNC_ACTION_DELETE".equals(String.valueOf(rawField(f, "action"))));
        assertThat(leave).as("퇴장 프레즌스를 받지 못함").isNotNull();
    }

    @Test
    @DisplayName("토큰을 Sec-WebSocket-Protocol 로 보내도 인증된다 (URL 에 토큰을 싣지 않는 경로)")
    void authenticatesViaSubProtocol() throws Exception {
        TestClient client = new TestClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        // 브라우저의 new WebSocket(url, [...]) 가 만드는 것과 같은 헤더.
        headers.setSecWebSocketProtocol(List.of("sharedsync.v1", "bearer." + ownerToken));
        client.session = new StandardWebSocketClient()
                .execute(client, headers, URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
        clients.add(client);

        assertThat(client.awaitFrame("hello", f -> true))
                .as("쿼리 파라미터 없이 핸드셰이크가 성립해야 한다 — URL 은 액세스 로그에 남는다")
                .isNotNull();

        client.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));
        assertThat(client.awaitFrame("presence", f -> true))
                .as("서브프로토콜로 인증된 사용자도 룸에 입장할 수 있어야 한다").isNotNull();
    }

    @Test
    @DisplayName("프레임 처리량과 세션 수가 메트릭으로 노출된다")
    void framesAndSessionsAreMetered() throws Exception {
        TestClient client = connect(ownerToken);
        client.send(joinFrame(plan.getPlanId().toString(), SyncSchema.SCHEMA_HASH));
        assertThat(client.awaitFrame("presence", f -> true)).isNotNull();

        // STOMP 시절에는 Spring 이 브로커 통계를 대신 냈다. raw WS 로 옮기며 사라졌으므로
        // 프레임워크가 직접 내야 한다 — 안 그러면 전환 후 상태를 볼 방법이 없다.
        assertThat(meterRegistry.find("sharedsync.frames").tag("type", "join").counter())
                .as("join 프레임 카운터가 없다").isNotNull();
        assertThat(meterRegistry.find("sharedsync.frames").tag("type", "join").counter().count())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.find("sharedsync.ws.sessions").gauge())
                .as("세션 수 게이지가 없다").isNotNull();
        assertThat(meterRegistry.find("sharedsync.ws.sessions").gauge().value())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("거부된 Join 은 에러 코드별로 집계된다")
    void rejectedJoinIsMeteredByErrorCode() throws Exception {
        TestClient client = connect(ownerToken);
        assertThat(client.awaitFrame("hello", f -> true)).isNotNull();
        client.send(joinFrame(plan.getPlanId().toString(), "deadbeefdeadbeef"));
        assertThat(client.awaitFrame("error", f -> true)).isNotNull();

        assertThat(meterRegistry.find("sharedsync.errors").tag("code", "SCHEMA_MISMATCH").counter())
                .as("에러 코드별 카운터가 없으면 스키마 스큐가 배포 후에도 안 보인다").isNotNull();
    }

    @Test
    @DisplayName("wire 스키마는 프레임워크가 서빙한다 — 앱이 엔드포인트를 만들 필요가 없다")
    void frameworkServesWireSchema() {
        ResponseEntity<String> response = new TestRestTemplate()
                .getForEntity("http://localhost:" + port + "/ws/schema.proto", String.class);

        assertThat(response.getStatusCode().value())
                .as("기본 경로가 WebSocket endpoint 하위라 앱의 시큐리티 화이트리스트(/ws/**)에 이미 들어 있다")
                .isEqualTo(200);
        assertThat(response.getBody())
                .as("클라이언트는 이 바이트로 코드를 생성하고 같은 바이트의 해시를 Join 에 싣는다")
                .isEqualTo(SyncSchema.PROTO_TEXT);
        assertThat(response.getHeaders().getFirst("X-SharedSync-Schema-Hash"))
                .isEqualTo(SyncSchema.SCHEMA_HASH);
    }

    // ==========================================
    // 프레임 조립
    // ==========================================

    private static DynamicMessage joinFrame(String roomId, String schemaHash) {
        Descriptor joinDesc = SCHEMA.findMessageTypeByName("Join");
        DynamicMessage join = DynamicMessage.newBuilder(joinDesc)
                .setField(joinDesc.findFieldByName("room_id"), roomId)
                .setField(joinDesc.findFieldByName("schema_hash"), schemaHash)
                .build();
        return clientFrame("join", join);
    }

    private static DynamicMessage editFrame(String eventId, String action, DynamicMessage block) {
        Descriptor listDesc = SCHEMA.findMessageTypeByName("TimeTablePlaceBlockList");
        DynamicMessage list = DynamicMessage.newBuilder(listDesc)
                .addRepeatedField(listDesc.findFieldByName("items"), block)
                .build();

        Descriptor requestDesc = SCHEMA.findMessageTypeByName("SyncRequest");
        DynamicMessage request = DynamicMessage.newBuilder(requestDesc)
                .setField(requestDesc.findFieldByName("event_id"), eventId)
                .setField(requestDesc.findFieldByName("action"),
                        requestDesc.findFieldByName("action").getEnumType().findValueByName(action))
                .setField(requestDesc.findFieldByName("time_table_place_blocks"), list)
                .build();
        return clientFrame("sync", request);
    }

    /** undo 는 payload oneof 를 설정하지 않는다 — action 만 실려 온다. */
    private static DynamicMessage undoFrame() {
        Descriptor requestDesc = SCHEMA.findMessageTypeByName("SyncRequest");
        DynamicMessage request = DynamicMessage.newBuilder(requestDesc)
                .setField(requestDesc.findFieldByName("event_id"), "raw-undo-" + UUID.randomUUID())
                .setField(requestDesc.findFieldByName("action"),
                        requestDesc.findFieldByName("action").getEnumType().findValueByName("SYNC_ACTION_UNDO"))
                .build();
        return clientFrame("sync", request);
    }

    private static DynamicMessage clientFrame(String arm, DynamicMessage value) {
        Descriptor frameDesc = SCHEMA.findMessageTypeByName("ClientFrame");
        return DynamicMessage.newBuilder(frameDesc)
                .setField(frameDesc.findFieldByName(arm), value)
                .build();
    }

    private static DynamicMessage block(java.util.function.Consumer<DynamicMessage.Builder> fill) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(
                SCHEMA.findMessageTypeByName("TimeTablePlaceBlock"));
        fill.accept(builder);
        return builder.build();
    }

    private static void setField(DynamicMessage.Builder builder, String name, Object value) {
        builder.setField(builder.getDescriptorForType().findFieldByName(name), value);
    }

    private static void setEnum(DynamicMessage.Builder builder, String name, String valueName) {
        FieldDescriptor fd = builder.getDescriptorForType().findFieldByName(name);
        builder.setField(fd, fd.getEnumType().findValueByName(valueName));
    }

    // ==========================================
    // 프레임 해석
    // ==========================================

    private static Object field(DynamicMessage message, String name) {
        FieldDescriptor fd = message.getDescriptorForType().findFieldByName(name);
        return message.hasField(fd) ? message.getField(fd) : null;
    }

    /** presence 를 갖지 않는 필드(enum·bool 등)용. 미설정이면 기본값이 나온다. */
    private static Object rawField(DynamicMessage message, String name) {
        return message.getField(message.getDescriptorForType().findFieldByName(name));
    }

    private static String enumName(DynamicMessage message, String name) {
        Object value = field(message, name);
        return value == null ? null : value.toString();
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

    // ==========================================
    // 테스트 클라이언트
    // ==========================================

    private TestClient connect(String token) throws Exception {
        TestClient client = new TestClient();
        client.session = new StandardWebSocketClient()
                .execute(client, null, URI.create("ws://localhost:" + port + "/ws?token=" + token))
                .get(5, TimeUnit.SECONDS);
        clients.add(client);
        return client;
    }

    /**
     * ServerFrame 을 arm 별로 모아두는 최소 클라이언트.
     *
     * STOMP 클라이언트와 달리 구독 개념이 없어 한 소켓으로 편집·프레즌스가 모두 들어온다.
     */
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

        /** 주어진 arm 이면서 조건을 만족하는 첫 프레임의 내용물. 없으면 null. */
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
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Exception ignored) {
                // 정리 단계라 실패해도 테스트 결과에 영향을 주지 않는다.
            }
        }
    }
}
