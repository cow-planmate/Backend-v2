package com.planmate.common.internal.grpc;

import build.buf.gen.planmate.internal.v1.RegionIndexServiceGrpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.beans.factory.annotation.Value;

/**
 * 내부 gRPC 호출의 토큰 인증.
 *
 * 서블릿 필터는 gRPC 요청에 걸리지 않으므로, 내부 API 인증은 <b>이 인터셉터가 유일하다.</b>
 * (HTTP {@code /api/internal/**} 는 호출부가 모두 gRPC 로 넘어간 뒤 제거했다.)
 * 이 인터셉터가 없으면 내부 사용자 API가 무인증으로 열린다.
 *
 * 토큰은 두 가지다.
 * <ul>
 *   <li>{@code internal.api-token} — Community·Social 이 공유한다. 모든 내부 서비스를 허용한다.</li>
 *   <li>{@code internal.ai-token} — planmate-ai 전용. 지역 색인 서비스만 허용한다. AI 는 지역 색인만
 *       필요한데 공유 토큰을 주면 사용자 정보 API 까지 열리기 때문이다(최소 권한).
 *       선택 설정이라 비어 있으면 아무 요청과도 일치하지 않는다 — 빈 헤더가 통과하는 일은 없다.</li>
 * </ul>
 * 모르는 토큰은 {@code UNAUTHENTICATED}, 아는 토큰이지만 허용 범위 밖이면 {@code PERMISSION_DENIED}.
 */
@GrpcGlobalServerInterceptor
public class InternalTokenServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    /** AI 토큰이 부를 수 있는 유일한 서비스. */
    static final String REGION_INDEX_SERVICE = RegionIndexServiceGrpc.SERVICE_NAME;

    @Value("${internal.api-token}")
    private String internalApiToken;

    @Value("${internal.ai-token:}")
    private String aiToken;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String token = headers.get(TOKEN_HEADER);
        if (token != null && token.equals(internalApiToken)) {
            return next.startCall(call, headers);
        }

        if (token != null && aiToken != null && !aiToken.isBlank() && token.equals(aiToken)) {
            if (REGION_INDEX_SERVICE.equals(call.getMethodDescriptor().getServiceName())) {
                return next.startCall(call, headers);
            }
            return reject(call, Status.PERMISSION_DENIED.withDescription("Internal token not allowed for this service"));
        }

        return reject(call, Status.UNAUTHENTICATED.withDescription("Invalid internal token"));
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
