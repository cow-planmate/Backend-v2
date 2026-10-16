package com.planmate.domain.destination.internal.grpc;

import build.buf.gen.planmate.internal.v1.GetRegionIndexRequest;
import build.buf.gen.planmate.internal.v1.GetRegionIndexResponse;
import build.buf.gen.planmate.internal.v1.RegionIndexServiceGrpc;
import build.buf.gen.planmate.internal.v1.RegionNode;
import com.planmate.common.internal.grpc.InternalTokenServerInterceptor;
import com.planmate.domain.destination.cache.RegionIndex;
import com.planmate.domain.destination.cache.RegionIndexCache;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RegionIndexGrpcServiceTest {

    private static final String SHARED_TOKEN = "shared-token-0123456789abcdef0123456789";
    private static final String AI_TOKEN = "ai-token-0123456789abcdef0123456789abcd";
    private static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    // 전남광주(시도) 아래 광주(구 2개만)
    private static final RegionIndex INDEX = new RegionIndex("v-current", List.of(
            new RegionIndex.Node(17, "광주", 155, List.of("광주광역시"),
                    List.of(new RegionIndex.CodePair("12", "210"), new RegionIndex.CodePair("12", "240"))),
            new RegionIndex.Node(155, "전남광주", null, List.of("전남", "전라남도"),
                    List.of(new RegionIndex.CodePair("12", null)))));

    @Mock
    private RegionIndexCache regionIndexCache;

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        InternalTokenServerInterceptor interceptor = new InternalTokenServerInterceptor();
        ReflectionTestUtils.setField(interceptor, "internalApiToken", SHARED_TOKEN);
        ReflectionTestUtils.setField(interceptor, "aiToken", AI_TOKEN);

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new RegionIndexGrpcService(regionIndexCache))
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

    private RegionIndexServiceGrpc.RegionIndexServiceBlockingStub stub(String token) {
        Metadata headers = new Metadata();
        headers.put(TOKEN_HEADER, token);
        return RegionIndexServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Test
    @DisplayName("버전이 없으면(첫 조회) AI 토큰으로 색인 전체를 받는다")
    void firstFetch_returnsFullIndex() {
        given(regionIndexCache.index()).willReturn(INDEX);

        GetRegionIndexResponse response = stub(AI_TOKEN).getRegionIndex(
                GetRegionIndexRequest.newBuilder().setKnownVersion("").build());

        assertThat(response.getUnchanged()).isFalse();
        assertThat(response.getVersion()).isEqualTo("v-current");
        assertThat(response.getNodesList()).extracting(RegionNode::getDestinationId).containsExactly(17, 155);

        RegionNode gwangju = response.getNodes(0);
        assertThat(gwangju.hasParentId()).isTrue();
        assertThat(gwangju.getParentId()).isEqualTo(155);
        assertThat(gwangju.getAliasesList()).containsExactly("광주광역시");
        assertThat(gwangju.getCodesList()).extracting(c -> c.getRegn() + "/" + c.getSigngu())
                .containsExactly("12/210", "12/240");

        RegionNode jeonnam = response.getNodes(1);
        assertThat(jeonnam.hasParentId()).isFalse();                 // 시도 노드
        assertThat(jeonnam.getCodes(0).hasSigngu()).isFalse();       // 시도 전체
    }

    @Test
    @DisplayName("가진 버전이 현재와 같으면 본문 없이 unchanged 만 보낸다")
    void sameVersion_returnsUnchanged() {
        given(regionIndexCache.index()).willReturn(INDEX);

        GetRegionIndexResponse response = stub(AI_TOKEN).getRegionIndex(
                GetRegionIndexRequest.newBuilder().setKnownVersion("v-current").build());

        assertThat(response.getUnchanged()).isTrue();
        assertThat(response.getNodesCount()).isZero();
    }

    @Test
    @DisplayName("옛 버전이면 새 색인 전체를 보낸다")
    void oldVersion_returnsFullIndex() {
        given(regionIndexCache.index()).willReturn(INDEX);

        GetRegionIndexResponse response = stub(AI_TOKEN).getRegionIndex(
                GetRegionIndexRequest.newBuilder().setKnownVersion("v-old").build());

        assertThat(response.getUnchanged()).isFalse();
        assertThat(response.getNodesCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("토큰이 틀리면 UNAUTHENTICATED — 색인은 조회하지 않는다")
    void wrongToken_unauthenticated() {
        assertThatThrownBy(() -> stub("wrong-token").getRegionIndex(GetRegionIndexRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }
}
