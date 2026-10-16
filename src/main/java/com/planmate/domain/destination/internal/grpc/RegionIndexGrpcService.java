package com.planmate.domain.destination.internal.grpc;

import build.buf.gen.planmate.internal.v1.GetRegionIndexRequest;
import build.buf.gen.planmate.internal.v1.GetRegionIndexResponse;
import build.buf.gen.planmate.internal.v1.RegionCodePair;
import build.buf.gen.planmate.internal.v1.RegionIndexServiceGrpc;
import build.buf.gen.planmate.internal.v1.RegionNode;
import com.planmate.domain.destination.cache.RegionIndex;
import com.planmate.domain.destination.cache.RegionIndexCache;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * planmate-ai 가 쓰는 지역 색인 조회 gRPC 서비스(planmate-ai/project_ai/22_지역_참조데이터_동기화_설계.md §5-3).
 *
 * 이 클래스는 전송 계층만 담당한다 — 색인은 기동 시 {@link RegionIndexCache}가 미리 만들어 둔다.
 * 호출부가 가진 버전과 같으면 본문 없이 {@code unchanged=true}만 보낸다.
 *
 * 인증은 common/internal/grpc의 InternalTokenServerInterceptor가 담당한다 — AI 전용 토큰은
 * 이 서비스만 허용된다.
 */
@GrpcService
@RequiredArgsConstructor
public class RegionIndexGrpcService extends RegionIndexServiceGrpc.RegionIndexServiceImplBase {

    private final RegionIndexCache regionIndexCache;

    @Override
    public void getRegionIndex(GetRegionIndexRequest request, StreamObserver<GetRegionIndexResponse> responseObserver) {
        RegionIndex index = regionIndexCache.index();

        GetRegionIndexResponse response;
        if (index.version().equals(request.getKnownVersion())) {
            response = GetRegionIndexResponse.newBuilder().setUnchanged(true).build();
        } else {
            GetRegionIndexResponse.Builder builder = GetRegionIndexResponse.newBuilder().setVersion(index.version());
            index.nodes().forEach(node -> builder.addNodes(toProto(node)));
            response = builder.build();
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private static RegionNode toProto(RegionIndex.Node node) {
        RegionNode.Builder builder = RegionNode.newBuilder()
                .setDestinationId(node.destinationId())
                .setName(node.name())
                .addAllAliases(node.aliases());
        if (node.parentId() != null) {
            builder.setParentId(node.parentId());
        }
        for (RegionIndex.CodePair code : node.codes()) {
            RegionCodePair.Builder pair = RegionCodePair.newBuilder().setRegn(code.regn());
            if (code.signgu() != null) {
                pair.setSigngu(code.signgu());
            }
            builder.addCodes(pair);
        }
        return builder.build();
    }
}
