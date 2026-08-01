package com.planmate.domain.user.internal.grpc;

import build.buf.gen.planmate.internal.v1.GetUsersRequest;
import build.buf.gen.planmate.internal.v1.GetUsersResponse;
import build.buf.gen.planmate.internal.v1.InternalUser;
import build.buf.gen.planmate.internal.v1.InternalUserServiceGrpc;
import build.buf.gen.planmate.internal.v1.WatchUserChangesRequest;
import build.buf.gen.planmate.internal.v1.WatchUserChangesResponse;
import com.planmate.domain.user.internal.InternalUserQueryService;
import com.planmate.domain.user.internal.InternalUserResponse;
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
 * 구독자 관리와 팬아웃은 UserChangeStreamRegistry가 맡는다.
 *
 * 인증은 common/internal/grpc의 InternalTokenServerInterceptor가 담당한다.
 * 서블릿 필터(InternalTokenFilter)는 gRPC 요청에 걸리지 않는다.
 */
@GrpcService
@RequiredArgsConstructor
public class InternalUserGrpcService extends InternalUserServiceGrpc.InternalUserServiceImplBase {

    private final InternalUserQueryService queryService;
    private final UserChangeStreamRegistry streamRegistry;

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
        streamRegistry.register((ServerCallStreamObserver<WatchUserChangesResponse>) responseObserver);
    }

    /**
     * proto3에는 null이 없으므로 미등록 값은 빈 문자열로 내려간다.
     * "없음"을 null로 표현하는 소비자는 수신측에서 되돌려야 한다.
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
