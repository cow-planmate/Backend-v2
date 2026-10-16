package com.planmate.common.internal.grpc;

import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰별 허용 범위 — 공유 토큰은 모든 내부 서비스, AI 토큰은 지역 색인 서비스만.
 * Community·Social 이 쓰는 공유 토큰의 동작이 바뀌지 않았는지가 이 테스트의 핵심이다.
 *
 * <p>생성 코드에 기대지 않도록 서비스 이름만 실제와 같은 가짜 서비스 두 개를 in-process 로 띄운다
 * (인터셉터가 보는 건 서비스 이름뿐이다).
 */
class InternalTokenServerInterceptorTest {

    private static final String SHARED_TOKEN = "shared-token-0123456789abcdef0123456789";
    private static final String AI_TOKEN = "ai-token-0123456789abcdef0123456789abcd";
    private static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    private static final MethodDescriptor<String, String> USER_METHOD =
            echoMethod("planmate.internal.v1.InternalUserService", "GetUsers");
    private static final MethodDescriptor<String, String> REGION_METHOD =
            echoMethod(InternalTokenServerInterceptor.REGION_INDEX_SERVICE, "GetRegionIndex");

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    private void start(String aiToken) throws IOException {
        InternalTokenServerInterceptor interceptor = new InternalTokenServerInterceptor();
        ReflectionTestUtils.setField(interceptor, "internalApiToken", SHARED_TOKEN);
        ReflectionTestUtils.setField(interceptor, "aiToken", aiToken);

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(echoService(USER_METHOD))
                .addService(echoService(REGION_METHOD))
                .intercept(interceptor)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private String callWith(MethodDescriptor<String, String> method, String token) {
        Metadata headers = new Metadata();
        if (token != null) {
            headers.put(TOKEN_HEADER, token);
        }
        return ClientCalls.blockingUnaryCall(
                ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers)),
                method, CallOptions.DEFAULT, "ping");
    }

    private Status.Code codeOf(MethodDescriptor<String, String> method, String token) {
        try {
            callWith(method, token);
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    // ---------- 공유 토큰 (Community·Social) — 기존 동작 유지 ----------

    @Test
    @DisplayName("공유 토큰은 사용자 서비스와 지역 색인 서비스 모두 통과한다")
    void sharedToken_allowsAllServices() throws IOException {
        start(AI_TOKEN);

        assertThat(callWith(USER_METHOD, SHARED_TOKEN)).isEqualTo("ping");
        assertThat(callWith(REGION_METHOD, SHARED_TOKEN)).isEqualTo("ping");
    }

    @Test
    @DisplayName("AI 토큰이 비어 있어도 공유 토큰 동작은 그대로다")
    void sharedToken_unaffectedByMissingAiToken() throws IOException {
        start("");

        assertThat(callWith(USER_METHOD, SHARED_TOKEN)).isEqualTo("ping");
    }

    @Test
    @DisplayName("토큰이 없거나 틀리면 UNAUTHENTICATED")
    void missingOrWrongToken_unauthenticated() throws IOException {
        start(AI_TOKEN);

        assertThat(codeOf(USER_METHOD, null)).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(codeOf(USER_METHOD, "wrong-token")).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(codeOf(REGION_METHOD, "wrong-token")).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    // ---------- AI 토큰 — 지역 색인만 ----------

    @Test
    @DisplayName("AI 토큰은 지역 색인 서비스만 통과하고, 사용자 서비스는 PERMISSION_DENIED")
    void aiToken_onlyRegionIndex() throws IOException {
        start(AI_TOKEN);

        assertThat(callWith(REGION_METHOD, AI_TOKEN)).isEqualTo("ping");
        assertThat(codeOf(USER_METHOD, AI_TOKEN)).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("AI 토큰이 비어 있으면 빈 헤더도 통과하지 못한다(UNAUTHENTICATED)")
    void blankAiToken_neverMatchesEmptyHeader() throws IOException {
        start("");

        assertThat(codeOf(REGION_METHOD, "")).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(codeOf(USER_METHOD, "")).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("AI 토큰이 설정되지 않았으면(null) 지역 색인도 UNAUTHENTICATED")
    void nullAiToken_regionIndexUnauthenticated() throws IOException {
        start(null);

        assertThat(codeOf(REGION_METHOD, AI_TOKEN)).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    // ---------- 가짜 서비스 ----------

    private static MethodDescriptor<String, String> echoMethod(String serviceName, String methodName) {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
                .setRequestMarshaller(StringMarshaller.INSTANCE)
                .setResponseMarshaller(StringMarshaller.INSTANCE)
                .build();
    }

    private static ServerServiceDefinition echoService(MethodDescriptor<String, String> method) {
        return ServerServiceDefinition.builder(method.getServiceName())
                .addMethod(method, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .build();
    }

    private enum StringMarshaller implements MethodDescriptor.Marshaller<String> {
        INSTANCE;

        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
