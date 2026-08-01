package com.planmate.common.internal.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.beans.factory.annotation.Value;

/**
 * 내부 gRPC 호출의 공유 토큰 인증.
 *
 * HTTP 쪽 InternalTokenFilter는 <b>서블릿 필터라 gRPC 요청에 걸리지 않는다.</b>
 * 이 인터셉터가 없으면 내부 사용자 API가 무인증으로 열린다.
 *
 * 검증 값은 InternalTokenFilter와 같은 {@code internal.api-token} 프로퍼티에서 읽어
 * 두 프로토콜의 토큰이 갈라지지 않게 한다.
 */
@GrpcGlobalServerInterceptor
public class InternalTokenServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    @Value("${internal.api-token}")
    private String internalApiToken;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String token = headers.get(TOKEN_HEADER);
        if (token == null || !token.equals(internalApiToken)) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid internal token"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        return next.startCall(call, headers);
    }
}
