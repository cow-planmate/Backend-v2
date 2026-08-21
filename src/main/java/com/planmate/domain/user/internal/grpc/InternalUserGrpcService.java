package com.planmate.domain.user.internal.grpc;

import build.buf.gen.planmate.internal.v1.GetUsersRequest;
import build.buf.gen.planmate.internal.v1.GetUsersResponse;
import build.buf.gen.planmate.internal.v1.InternalUser;
import build.buf.gen.planmate.internal.v1.InternalUserServiceGrpc;
import build.buf.gen.planmate.internal.v1.WatchUserChangesRequest;
import build.buf.gen.planmate.internal.v1.WatchUserChangesResponse;
import com.planmate.domain.user.internal.InternalUserQueryService;
import com.planmate.domain.user.internal.InternalUserResponse;
import com.planmate.domain.user.internal.outbox.UserOutboxRepository;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;
import java.util.UUID;

/**
 * 커뮤니티 서비스가 쓰는 내부 사용자 조회 gRPC 서비스.
 *
 * 이 클래스는 전송 계층만 담당한다 — 조회는 InternalUserQueryService(HTTP 엔드포인트와 공유),
 * 스트림 세션의 전송과 커서 전진은 UserChangeStreamPoller가 맡는다.
 *
 * 인증은 common/internal/grpc의 InternalTokenServerInterceptor가 담당한다.
 * 서블릿 필터는 gRPC 요청에 걸리지 않는다.
 */
@GrpcService
@RequiredArgsConstructor
public class InternalUserGrpcService extends InternalUserServiceGrpc.InternalUserServiceImplBase {

    private final InternalUserQueryService queryService;
    private final UserOutboxRepository outboxRepository;
    private final UserChangeStreamPoller poller;

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

    /**
     * 사용자 변경 스트림. 요청의 from_sequence 해석이 이 메서드의 전부다.
     *
     * <p>세 갈래인 이유는 Watchtower 가 두 서비스를 따로 갱신해 구/신 버전이 섞여 도는 구간이
     * 반드시 있기 때문이다. 필드 <b>존재 여부</b>가 의미를 가지므로 proto 에서 optional 로 뒀다.
     */
    @Override
    public void watchUserChanges(WatchUserChangesRequest request,
                                 StreamObserver<WatchUserChangesResponse> responseObserver) {
        ServerCallStreamObserver<WatchUserChangesResponse> observer =
                (ServerCallStreamObserver<WatchUserChangesResponse>) responseObserver;
        UserChangeStreamSession session = new UserChangeStreamSession(observer);

        // 끊긴 세션은 반드시 제거해야 한다 — 남겨두면 죽은 스트림에 계속 쓰려 한다
        observer.setOnCancelHandler(session::cancel);
        observer.setOnCloseHandler(session::cancel);
        // 흐름 제어가 풀리면 멈춰 있던 지점부터 이어서 보낸다
        observer.setOnReadyHandler(poller::wakeUp);

        if (!request.hasFromSequence()) {
            // 구버전 소비자. 커서를 모르므로 재생하지 않고 "지금부터"만 — 예전과 같은 동작이다.
            // 페이로드는 실어 보내도 구버전 생성 코드가 unknown field 로 무시하므로 분기하지 않는다.
            session.startTail(outboxRepository.currentMaxSeq());
        } else if (request.getFromSequence() == 0
                || request.getFromSequence() < outboxRepository.oldestRetainedSeq() - 1) {
            // 복제본이 비었거나(0), 커서가 보존 기간 밖으로 밀려나 중간이 이미 정리됐다.
            // 조용히 이어붙이면 빈 구간이 영구히 남으므로 전체 스냅샷으로 격하한다.
            //
            // snapshotSeq 는 users 를 읽기 전에 확보한다. 스냅샷을 보내는 동안 일어난 변경은
            // 이 값 이후로 다시 재생되므로 유실되지 않는다(중복은 소비자의 멱등 업서트가 흡수).
            session.startSnapshot(outboxRepository.currentMaxSeq());
        } else {
            session.startTail(request.getFromSequence());
        }

        poller.register(session);
    }

    /**
     * proto3에는 null이 없으므로 미등록 값은 빈 문자열로 내려간다.
     * "없음"을 null로 표현하는 소비자는 수신측에서 되돌려야 한다.
     */
    private InternalUser toProto(InternalUserResponse user) {
        InternalUser.Builder builder = InternalUser.newBuilder()
                .setUserId(user.userId().toString())
                .setProfilePublic(user.profilePublic())
                .setDeleted(user.deleted());

        // 탈퇴 계정은 세 필드가 모두 null이라 그대로 빈 문자열로 나간다
        if (user.nickname() != null) {
            builder.setNickname(user.nickname());
        }
        if (user.profileImageUrl() != null) {
            builder.setProfileImageUrl(user.profileImageUrl());
        }
        if (user.avatarHash() != null) {
            builder.setAvatarHash(user.avatarHash());
        }
        return builder.build();
    }
}
