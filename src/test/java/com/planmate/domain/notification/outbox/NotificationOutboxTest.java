package com.planmate.domain.notification.outbox;

import build.buf.gen.planmate.notification.v1.ActorSnapshot;
import build.buf.gen.planmate.notification.v1.NotificationRequested;
import build.buf.gen.planmate.notification.v1.NotificationType;
import build.buf.gen.planmate.notification.v1.ResourceRef;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * outbox 가 지켜야 하는 계약: 도메인 트랜잭션과 운명을 같이 하고, Kafka 가 죽어도 업무는 성공하며,
 * 재시도로 같은 이벤트가 여러 번 나가도 소비자가 eventId 로 지울 수 있어야 한다.
 *
 * <p>브로커는 띄우지 않는다 — 검증 대상은 전송 성공/실패에 따른 <b>outbox 행의 상태 전이</b>이고,
 * KafkaTemplate 을 목으로 두면 "브로커가 죽은 상황"을 결정적으로 재현할 수 있다.
 */
@SpringBootTest
class NotificationOutboxTest {

    @Autowired
    private NotificationOutboxWriter writer;
    @Autowired
    private NotificationOutboxPublisher publisher;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private KafkaTemplate<String, byte[]> kafka;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM notification_outbox");
    }

    @Test
    @DisplayName("도메인 트랜잭션이 롤백되면 알림 이벤트도 남지 않는다")
    void rollsBackWithTheDomainTransaction() {
        NotificationRequested event = event(UUID.randomUUID());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            writer.publish(event);
            throw new IllegalStateException("도메인 규칙 위반");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 밖에서는 이벤트를 쓸 수 없다 — 커밋되지 않은 상태 변경의 알림을 막는다")
    void refusesToWriteOutsideATransaction() {
        assertThatThrownBy(() -> writer.publish(event(UUID.randomUUID())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("Kafka 가 죽어 있어도 업무 트랜잭션은 성공하고 이벤트는 미발행으로 남아 밀린다")
    void keepsEventPendingWhenBrokerIsDown() {
        given(kafka.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        UUID recipient = UUID.randomUUID();
        transactions.executeWithoutResult(status -> writer.publish(event(recipient)));

        publisher.publishDue();

        Map<String, Object> row = rows().get(0);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_error").toString()).contains("broker down");
        assertThat(jdbc.queryForObject(
                "SELECT next_attempt_at > now() FROM notification_outbox", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("브로커가 살아나면 밀려 있던 이벤트가 발행되고 다시 보내지 않는다")
    void publishesOnceAfterRecovery() {
        given(kafka.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        UUID recipient = UUID.randomUUID();
        NotificationRequested event = event(recipient);
        transactions.executeWithoutResult(status -> writer.publish(event));

        publisher.publishDue();
        publisher.publishDue();

        verify(kafka).send(anyString(), anyString(), any());
        assertThat(rows().get(0).get("published_at")).isNotNull();
    }

    @Test
    @DisplayName("수신자 ID 를 partition key 로 쓴다 — 같은 사용자의 알림 순서가 보장된다")
    void partitionsByRecipient() {
        given(kafka.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        UUID recipient = UUID.randomUUID();
        transactions.executeWithoutResult(status -> writer.publish(event(recipient)));

        publisher.publishDue();

        verify(kafka).send(anyString(), org.mockito.ArgumentMatchers.eq(recipient.toString()), any());
    }

    @Test
    @DisplayName("같은 eventId 를 두 번 쓰면 한 행만 남는다 — 생산자 재시도가 알림을 늘리지 않는다")
    void deduplicatesByEventId() {
        NotificationRequested event = event(UUID.randomUUID());

        transactions.executeWithoutResult(status -> writer.publish(event));
        transactions.executeWithoutResult(status -> writer.publish(event));

        assertThat(rows()).hasSize(1);
    }

    @Test
    @DisplayName("실린 payload 는 소비자가 그대로 파싱할 수 있는 Protobuf 다")
    void storesConsumableProtobufPayload() throws Exception {
        UUID recipient = UUID.randomUUID();
        NotificationRequested event = event(recipient);
        transactions.executeWithoutResult(status -> writer.publish(event));

        byte[] payload = jdbc.queryForObject("SELECT payload FROM notification_outbox", byte[].class);

        NotificationRequested parsed = NotificationRequested.parseFrom(payload);
        assertThat(parsed.getEventId()).isEqualTo(event.getEventId());
        assertThat(parsed.getRecipientUserId()).isEqualTo(recipient.toString());
        assertThat(parsed.getType()).isEqualTo(NotificationType.NOTIFICATION_TYPE_PLAN_INVITED);
        assertThat(parsed.getResource().getDisplayName()).isEqualTo("제주 3박4일");
    }

    @Test
    @DisplayName("발행이 끝난 지 30일이 지난 행만 정리하고, 아직 못 보낸 행은 절대 지우지 않는다")
    void prunesOnlyOldPublishedRows() {
        transactions.executeWithoutResult(status -> writer.publish(event(UUID.randomUUID())));
        transactions.executeWithoutResult(status -> writer.publish(event(UUID.randomUUID())));
        List<Map<String, Object>> before = rows();
        jdbc.update("UPDATE notification_outbox SET published_at = now() - interval '40 days' WHERE event_id = ?",
                before.get(0).get("event_id"));

        publisher.prune();

        assertThat(rows()).hasSize(1);
        assertThat(rows().get(0).get("published_at")).isNull();
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT * FROM notification_outbox ORDER BY created_at");
    }

    private static NotificationRequested event(UUID recipient) {
        Instant now = Instant.now();
        return NotificationRequested.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setProducer("backend-v2")
                .setOccurredAt(Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build())
                .setRecipientUserId(recipient.toString())
                .setType(NotificationType.NOTIFICATION_TYPE_PLAN_INVITED)
                .setActor(ActorSnapshot.newBuilder()
                        .setUserId(UUID.randomUUID().toString()).setDisplayName("초대한사람").build())
                .setResource(ResourceRef.newBuilder()
                        .setType("PLAN").setId("7").setDisplayName("제주 3박4일").build())
                .build();
    }
}
