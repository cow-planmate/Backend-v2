package com.planmate.domain.user.internal.grpc;

import build.buf.gen.planmate.internal.v1.GetUsersRequest;
import build.buf.gen.planmate.internal.v1.GetUsersResponse;
import build.buf.gen.planmate.internal.v1.InternalUser;
import build.buf.gen.planmate.internal.v1.InternalUserServiceGrpc;
import build.buf.gen.planmate.internal.v1.WatchUserChangesRequest;
import build.buf.gen.planmate.internal.v1.WatchUserChangesResponse;
import com.planmate.common.internal.grpc.InternalTokenServerInterceptor;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.internal.InternalUserQueryService;
import com.planmate.domain.user.internal.InternalUserResponse;
import com.planmate.domain.user.internal.outbox.UserOutboxEntry;
import com.planmate.domain.user.internal.outbox.UserOutboxProperties;
import com.planmate.domain.user.internal.outbox.UserOutboxRepository;
import com.planmate.domain.user.repository.UserRepository;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class InternalUserGrpcServiceTest {

    private static final String TOKEN = "test-internal-token";
    private static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    @Mock
    private InternalUserQueryService queryService;

    @Mock
    private UserOutboxRepository outboxRepository;

    @Mock
    private UserRepository userRepository;

    private UserChangeStreamPoller poller;
    private InternalUserGrpcService service;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        UserOutboxProperties properties = new UserOutboxProperties();
        // 테스트가 정기 틱을 기다리지 않도록 짧게. 실제 전달은 register()의 즉시 wakeUp이 한다.
        properties.setPollIntervalMs(50);
        properties.setBatchSize(10);

        poller = new UserChangeStreamPoller(outboxRepository, userRepository, properties);
        ReflectionTestUtils.invokeMethod(poller, "start");
        service = new InternalUserGrpcService(queryService, outboxRepository, poller);

        InternalTokenServerInterceptor interceptor = new InternalTokenServerInterceptor();
        ReflectionTestUtils.setField(interceptor, "internalApiToken", TOKEN);

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .intercept(interceptor)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        ReflectionTestUtils.invokeMethod(poller, "stop");
    }

    private InternalUserServiceGrpc.InternalUserServiceBlockingStub authedStub() {
        Metadata headers = new Metadata();
        headers.put(TOKEN_HEADER, TOKEN);
        return InternalUserServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Test
    @DisplayName("유효한 ID를 조회하면 사용자 정보를 반환한다")
    void getUsers_returnsUsers() {
        UUID userId = UUID.randomUUID();
        given(queryService.findUsers(anyList())).willReturn(List.of(
                new InternalUserResponse(userId, "홍길동", true, "https://img/1.png", "abc123", false)));

        GetUsersResponse response = authedStub().getUsers(
                GetUsersRequest.newBuilder().addUserIds(userId.toString()).build());

        assertThat(response.getUsersCount()).isEqualTo(1);
        InternalUser user = response.getUsers(0);
        assertThat(user.getUserId()).isEqualTo(userId.toString());
        assertThat(user.getNickname()).isEqualTo("홍길동");
        assertThat(user.getProfilePublic()).isTrue();
        assertThat(user.getProfileImageUrl()).isEqualTo("https://img/1.png");
        assertThat(user.getAvatarHash()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("null인 프로필 이미지·아바타 해시는 빈 문자열로 내려간다")
    void getUsers_mapsNullToEmptyString() {
        UUID userId = UUID.randomUUID();
        given(queryService.findUsers(anyList())).willReturn(List.of(
                new InternalUserResponse(userId, "익명", false, null, null, false)));

        InternalUser user = authedStub().getUsers(
                GetUsersRequest.newBuilder().addUserIds(userId.toString()).build()).getUsers(0);

        assertThat(user.getProfileImageUrl()).isEmpty();
        assertThat(user.getAvatarHash()).isEmpty();
    }

    @Test
    @DisplayName("UUID 형식이 아닌 ID는 INVALID_ARGUMENT")
    void getUsers_rejectsMalformedId() {
        assertThatThrownBy(() -> authedStub().getUsers(
                GetUsersRequest.newBuilder().addUserIds("not-a-uuid").build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("빈 ID 목록은 INVALID_ARGUMENT")
    void getUsers_rejectsEmptyIds() {
        assertThatThrownBy(() -> authedStub().getUsers(GetUsersRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);

        verifyNoInteractions(queryService);
    }

    // 서블릿 필터는 gRPC에 걸리지 않는다. 인터셉터가 유일한 방어선이므로
    // 아래 두 테스트가 이 전환에서 가장 중요하다.

    @Test
    @DisplayName("토큰이 없으면 UNAUTHENTICATED")
    void rejectsMissingToken() {
        assertThatThrownBy(() -> InternalUserServiceGrpc.newBlockingStub(channel).getUsers(
                GetUsersRequest.newBuilder().addUserIds(UUID.randomUUID().toString()).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("토큰이 틀리면 UNAUTHENTICATED")
    void rejectsWrongToken() {
        Metadata headers = new Metadata();
        headers.put(TOKEN_HEADER, "wrong-token");
        var stub = InternalUserServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.getUsers(
                GetUsersRequest.newBuilder().addUserIds(UUID.randomUUID().toString()).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("커서를 보내면 그 이후 변경이 값과 함께 전달된다")
    void watchUserChanges_replaysFromCursor() throws Exception {
        UUID userId = UUID.randomUUID();
        given(outboxRepository.oldestRetainedSeq()).willReturn(1L);
        given(outboxRepository.findAfter(eq(7L), anyInt()))
                .willReturn(List.of(new UserOutboxEntry(8L, userId, "바뀐닉", null, "hash", true, false)))
                .willReturn(List.of());

        LinkedBlockingQueue<WatchUserChangesResponse> received = subscribe(
                WatchUserChangesRequest.newBuilder().setFromSequence(7L).build());

        WatchUserChangesResponse event = received.poll(2, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.getUserId()).isEqualTo(userId.toString());
        assertThat(event.getSequence()).isEqualTo(8L);
        // 값을 함께 싣는다 — 소비자가 GetUsers로 되묻지 않아도 복제본을 갱신할 수 있어야 한다
        assertThat(event.hasUser()).isTrue();
        assertThat(event.getUser().getNickname()).isEqualTo("바뀐닉");
        assertThat(event.getUser().getProfilePublic()).isTrue();
    }

    @Test
    @DisplayName("커서를 보내지 않는 구버전 소비자에게는 재생 없이 지금부터만 전달된다")
    void watchUserChanges_legacyConsumerTailsFromNow() throws Exception {
        given(outboxRepository.currentMaxSeq()).willReturn(42L);
        given(outboxRepository.findAfter(eq(42L), anyInt())).willReturn(List.of());

        subscribe(WatchUserChangesRequest.getDefaultInstance());

        // 과거를 재생하지 않는다 — 구버전은 커서를 모르므로 예전과 같은 "지금부터" 의미여야 한다.
        // 폴러가 주기적으로 돌므로 호출 횟수가 아니라 "어느 지점부터 읽는가"만 본다.
        verify(outboxRepository, timeout(2000).atLeastOnce()).findAfter(eq(42L), anyInt());
        verify(outboxRepository, never()).findAfter(eq(0L), anyInt());
    }

    @Test
    @DisplayName("커서가 0이면 전체 스냅샷을 보낸 뒤 완료를 알린다")
    void watchUserChanges_emptyProjectionGetsSnapshot() throws Exception {
        given(outboxRepository.currentMaxSeq()).willReturn(5L);
        given(userRepository.findSnapshotPageIncludingDeleted(any(UUID.class), anyInt()))
                .willReturn(List.of(snapshotUser("스냅닉")))
                .willReturn(List.of());
        LinkedBlockingQueue<WatchUserChangesResponse> received = subscribe(
                WatchUserChangesRequest.newBuilder().setFromSequence(0L).build());

        WatchUserChangesResponse entry = received.poll(2, TimeUnit.SECONDS);
        assertThat(entry).isNotNull();
        assertThat(entry.getUser().getNickname()).isEqualTo("스냅닉");
        // 스냅샷 항목은 전부 기준 시퀀스를 달고 나간다
        assertThat(entry.getSequence()).isEqualTo(5L);

        WatchUserChangesResponse done = received.poll(2, TimeUnit.SECONDS);
        assertThat(done).isNotNull();
        // 소비자는 이 표시를 받은 시점에만 커서를 영속화해야 한다
        assertThat(done.getSnapshotComplete()).isTrue();
        assertThat(done.getSequence()).isEqualTo(5L);
    }

    @Test
    @DisplayName("커서가 보존 기간 밖이면 조용히 이어붙이지 않고 스냅샷으로 격하한다")
    void watchUserChanges_prunedCursorFallsBackToSnapshot() throws Exception {
        // 소비자 커서(3)보다 남아 있는 가장 오래된 시퀀스(100)가 더 크다 = 중간이 이미 정리됐다
        given(outboxRepository.oldestRetainedSeq()).willReturn(100L);
        given(outboxRepository.currentMaxSeq()).willReturn(150L);
        given(userRepository.findSnapshotPageIncludingDeleted(any(UUID.class), anyInt()))
                .willReturn(List.of());

        LinkedBlockingQueue<WatchUserChangesResponse> received = subscribe(
                WatchUserChangesRequest.newBuilder().setFromSequence(3L).build());

        WatchUserChangesResponse done = received.poll(2, TimeUnit.SECONDS);
        assertThat(done).isNotNull();
        assertThat(done.getSnapshotComplete()).isTrue();
        // 빈 구간을 남긴 채 이어붙였다면 findAfter(3, ...)를 불렀을 것이다
        verify(outboxRepository, never()).findAfter(eq(3L), anyInt());
    }

    private LinkedBlockingQueue<WatchUserChangesResponse> subscribe(WatchUserChangesRequest request) {
        LinkedBlockingQueue<WatchUserChangesResponse> received = new LinkedBlockingQueue<>();
        Metadata headers = new Metadata();
        headers.put(TOKEN_HEADER, TOKEN);
        InternalUserServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .watchUserChanges(request, new StreamObserver<>() {
                    @Override
                    public void onNext(WatchUserChangesResponse value) {
                        received.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        return received;
    }

    private User snapshotUser(String nickname) {
        User user = User.builder()
                .provider(UserProvider.LOCAL)
                .email("snap@example.com")
                .nickname(nickname)
                .build();
        ReflectionTestUtils.setField(user, "userId", UUID.randomUUID());
        return user;
    }
}
