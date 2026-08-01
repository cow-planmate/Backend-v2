package com.planmate.common.internal.grpc;

import build.buf.gen.planmate.internal.v1.GetUsersRequest;
import build.buf.gen.planmate.internal.v1.GetUsersResponse;
import build.buf.gen.planmate.internal.v1.InternalUser;
import build.buf.gen.planmate.internal.v1.InternalUserServiceGrpc;
import build.buf.gen.planmate.internal.v1.WatchUserChangesRequest;
import build.buf.gen.planmate.internal.v1.WatchUserChangesResponse;
import com.planmate.common.internal.InternalUserQueryService;
import com.planmate.common.internal.InternalUserResponse;
import com.planmate.common.internal.UserChangePublisher;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * 내부 사용자 조회 gRPC 서비스. 조회 로직은 HTTP 엔드포인트와 InternalUserQueryService를 공유한다.
 *
 * 인증은 InternalTokenServerInterceptor가 담당한다 — 서블릿 필터는 여기 걸리지 않는다.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class InternalUserGrpcService extends InternalUserServiceGrpc.InternalUserServiceImplBase
        implements UserChangePublisher {

    private final InternalUserQueryService queryService;

    /**
     * 열려 있는 WatchUserChanges 스트림들. 구독자 수가 서비스 인스턴스 수준(한 자릿수)이라
     * 쓰기 드문 CopyOnWriteArraySet으로 충분하다.
     *
     * 연결이 끊긴 옵저버를 반드시 제거해야 한다 — 남겨두면 죽은 스트림에 계속 write 해 힙이 찬다.
     */
    private final Set<ServerCallStreamObserver<WatchUserChangesResponse>> watchers = new CopyOnWriteArraySet<>();

    @Override
    public void getUsers(GetUsersRequest request, StreamObserver<GetUsersResponse> responseObserver) {
        List<UUID> userIds;
        try {
            userIds = request.getUserIdsList().stream().map(UUID::fromString).toList();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("UUID 형식이 아닌 사용자 ID가 있습니다.")
                    .asRuntimeException());
            return;
        }

        if (userIds.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("조회할 사용자 ID가 없습니다.")
                    .asRuntimeException());
            return;
        }

        GetUsersResponse.Builder response = GetUsersResponse.newBuilder();
        for (InternalUserResponse user : queryService.findUsers(userIds)) {
            response.addUsers(toProto(user));
        }

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void watchUserChanges(WatchUserChangesRequest request,
                                 StreamObserver<WatchUserChangesResponse> responseObserver) {
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<WatchUserChangesResponse> observer =
                (ServerCallStreamObserver<WatchUserChangesResponse>) responseObserver;

        // 취소/종료 어느 쪽으로 끝나든 등록을 해제한다
        observer.setOnCancelHandler(() -> watchers.remove(observer));
        observer.setOnCloseHandler(() -> watchers.remove(observer));

        watchers.add(observer);
        log.debug("사용자 변경 스트림 구독 시작 (구독자 수={})", watchers.size());
    }

    /**
     * 트랜잭션 커밋 이후 UserChangeNotifier가 호출한다.
     * 한 구독자에게 실패해도 나머지에게는 계속 보낸다 — 캐시 무효화는 best-effort다.
     */
    @Override
    public void publish(UUID userId) {
        WatchUserChangesResponse event = WatchUserChangesResponse.newBuilder()
                .setUserId(userId.toString())
                .build();

        for (ServerCallStreamObserver<WatchUserChangesResponse> observer : watchers) {
            try {
                // StreamObserver는 스레드 안전하지 않다. 동시 커밋에서 onNext가 겹치면
                // 프레임이 섞이므로 옵저버 단위로 직렬화한다.
                synchronized (observer) {
                    if (observer.isCancelled()) {
                        watchers.remove(observer);
                        continue;
                    }
                    observer.onNext(event);
                }
            } catch (Exception e) {
                watchers.remove(observer);
                log.warn("사용자 변경 알림 발행 실패 (userId={}): {}", userId, e.getMessage());
            }
        }
    }

    /**
     * proto3에는 null이 없으므로 미등록 값은 빈 문자열로 내려간다.
     * 수신측(Community)이 빈 문자열을 다시 null로 되돌린다.
     */
    private InternalUser toProto(InternalUserResponse user) {
        InternalUser.Builder builder = InternalUser.newBuilder()
                .setUserId(user.userId().toString())
                .setNickname(user.nickname())
                .setProfilePublic(user.profilePublic());

        if (user.profileImageUrl() != null) {
            builder.setProfileImageUrl(user.profileImageUrl());
        }
        if (user.avatarHash() != null) {
            builder.setAvatarHash(user.avatarHash());
        }
        return builder.build();
    }
}
