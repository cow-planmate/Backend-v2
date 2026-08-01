package com.planmate.common.internal.grpc;

import build.buf.gen.planmate.internal.v1.GetUsersRequest;
import build.buf.gen.planmate.internal.v1.GetUsersResponse;
import build.buf.gen.planmate.internal.v1.InternalUser;
import build.buf.gen.planmate.internal.v1.InternalUserServiceGrpc;
import build.buf.gen.planmate.internal.v1.WatchUserChangesRequest;
import build.buf.gen.planmate.internal.v1.WatchUserChangesResponse;
import com.planmate.common.internal.InternalUserQueryService;
import com.planmate.common.internal.InternalUserResponse;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class InternalUserGrpcServiceTest {

    private static final String TOKEN = "test-internal-token";
    private static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    @Mock
    private InternalUserQueryService queryService;

    private InternalUserGrpcService service;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        service = new InternalUserGrpcService(queryService);

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
                new InternalUserResponse(userId, "홍길동", true, "https://img/1.png", "abc123")));

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
                new InternalUserResponse(userId, "익명", false, null, null)));

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

    // 서블릿 필터(InternalTokenFilter)는 gRPC에 걸리지 않는다. 인터셉터가 유일한 방어선이므로
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
    @DisplayName("구독자에게 사용자 변경 이벤트가 전달된다")
    void watchUserChanges_deliversEvent() throws Exception {
        UUID userId = UUID.randomUUID();
        LinkedBlockingQueue<WatchUserChangesResponse> received = new LinkedBlockingQueue<>();

        Metadata headers = new Metadata();
        headers.put(TOKEN_HEADER, TOKEN);
        InternalUserServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .watchUserChanges(WatchUserChangesRequest.getDefaultInstance(),
                        new StreamObserver<>() {
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

        service.publish(userId);

        WatchUserChangesResponse event = received.poll(2, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.getUserId()).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("구독자가 끊기면 옵저버가 해제돼 이후 발행이 누수되지 않는다")
    void watchUserChanges_removesCancelledObserver() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        Metadata headers = new Metadata();
        headers.put(TOKEN_HEADER, TOKEN);

        var call = InternalUserServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        call.watchUserChanges(WatchUserChangesRequest.getDefaultInstance(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(WatchUserChangesResponse value) {
                        subscribed.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        service.publish(UUID.randomUUID());
        assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(watchers()).hasSize(1);

        channel.shutdownNow();
        channel.awaitTermination(2, TimeUnit.SECONDS);

        // 취소가 서버에 전파되면 옵저버가 해제된다
        for (int i = 0; i < 50 && !watchers().isEmpty(); i++) {
            service.publish(UUID.randomUUID());
            Thread.sleep(20);
        }
        assertThat(watchers()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<Object> watchers() {
        return (java.util.Set<Object>) ReflectionTestUtils.getField(service, "watchers");
    }
}
