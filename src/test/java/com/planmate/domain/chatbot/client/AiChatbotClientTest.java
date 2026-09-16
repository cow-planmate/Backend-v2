package com.planmate.domain.chatbot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planmate.common.exception.domain.ChatbotAiMisconfiguredException;
import com.planmate.common.exception.domain.ChatbotAiUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class AiChatbotClientTest {

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final String URL = "http://ai-test/api/chatbot";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private AiChatbotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai-test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiChatbotClient(builder.build());
    }

    private ObjectNode body() {
        return objectMapper.createObjectNode().put("message", "안녕");
    }

    @Test
    @DisplayName("2xx — 응답 본문을 JsonNode 로 그대로 반환한다")
    void success_passesBodyThrough() {
        server.expect(requestTo(URL)).andExpect(method(POST))
                .andRespond(withSuccess("{\"userMessage\":\"짰어요\",\"plan\":null,\"shownPlaces\":[]}",
                        MediaType.APPLICATION_JSON));

        JsonNode result = client.call(body(), PLAN_ID);

        assertThat(result.get("userMessage").asText()).isEqualTo("짰어요");
        assertThat(result.get("plan").isNull()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("401 → ChatbotAiMisconfiguredException (500)")
    void status401_misconfigured() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.call(body(), PLAN_ID))
                .isInstanceOf(ChatbotAiMisconfiguredException.class);
    }

    @Test
    @DisplayName("422 → ChatbotAiMisconfiguredException (500)")
    void status422_misconfigured() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> client.call(body(), PLAN_ID))
                .isInstanceOf(ChatbotAiMisconfiguredException.class);
    }

    @Test
    @DisplayName("502 → ChatbotAiUnavailableException (503)")
    void status502_unavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client.call(body(), PLAN_ID))
                .isInstanceOf(ChatbotAiUnavailableException.class);
    }

    @Test
    @DisplayName("503 → ChatbotAiUnavailableException")
    void status503_unavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.call(body(), PLAN_ID))
                .isInstanceOf(ChatbotAiUnavailableException.class);
    }

    @Test
    @DisplayName("connect refused → 1s 후 1회 재시도, 그래도 실패하면 ChatbotAiUnavailableException")
    void connectRefused_retriesOnceThenUnavailable() {
        server.expect(times(2), requestTo(URL))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.call(body(), PLAN_ID))
                .isInstanceOf(ChatbotAiUnavailableException.class);
        server.verify();   // 정확히 2회 호출됐는지
    }

    @Test
    @DisplayName("read timeout(연결 실패 아님) → 재시도 없이 ChatbotAiUnavailableException")
    void readTimeout_noRetry() {
        server.expect(times(1), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.call(body(), PLAN_ID))
                .isInstanceOf(ChatbotAiUnavailableException.class);
        server.verify();   // 1회만
    }
}
