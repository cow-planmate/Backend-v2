package com.planmate.domain.user.internal.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox 시퀀스가 <b>커밋 순서</b>와 일치하는지 검증한다. 이 프로젝트에서 가장 위험한 가정이다.
 *
 * <p>배경: BIGSERIAL 의 값은 INSERT 시점에 정해지지만 행은 COMMIT 시점에 보인다. 그래서 seq=10 을
 * 뽑은 느린 트랜잭션보다 seq=11 이 먼저 커밋되면, {@code WHERE seq > 커서 ORDER BY seq} 로 읽는
 * 소비자가 11 을 보고 커서를 11 로 올려 <b>10 을 영원히 건너뛴다.</b> 캐시 시절에는 TTL 이 이걸
 * 덮어줬지만 복제 테이블에는 그런 게 없어서 영구 발산이 된다. 게다가 조용히 일어난다.
 *
 * <p>{@link UserOutboxWriter} 가 INSERT 직전에 {@code pg_advisory_xact_lock} 을 잡는 이유가
 * 이것이고, 이 테스트는 그 락이 실제로 효과가 있는지를 두 트랜잭션을 겹쳐서 확인한다.
 * 락을 빼면 실패하도록 음성 대조군({@link #withoutLock_consumerSkipsRow})도 함께 둔다 —
 * 그게 없으면 이 테스트가 락과 무관하게 통과하는지 알 수 없다.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 순수 JDBC 로 도는 이유는, 검증 대상이 애플리케이션 코드가
 * 아니라 PostgreSQL 의 잠금·가시성 동작 자체이기 때문이다.
 */
@Testcontainers
class UserOutboxCommitOrderTest {

    /** UserOutboxWriter 와 같은 키여야 의미가 있다. */
    private static final long PUBLISH_LOCK_KEY = 4_919_001L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeEach
    void createTable() throws Exception {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS user_outbox");
            // V6__user_outbox.sql 과 같은 형태 (이 테스트에 필요한 컬럼만)
            st.execute("""
                    CREATE TABLE user_outbox (
                        seq     BIGSERIAL PRIMARY KEY,
                        user_id UUID NOT NULL
                    )
                    """);
        }
    }

    /**
     * 트랜잭션 하나를 열어 outbox 행을 넣고, 신호를 받을 때까지 커밋을 미룬다.
     * 실제 코드에서 이 지연에 해당하는 것은 프로필 이미지 교체의 MinIO 업로드 같은 느린 작업이다.
     */
    private Thread slowWriter(UUID userId, boolean useLock, CountDownLatch inserted,
                              CountDownLatch mayCommit, List<Throwable> errors) {
        return new Thread(() -> {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                if (useLock) {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                        ps.setLong(1, PUBLISH_LOCK_KEY);
                        ps.execute();
                    }
                }
                try (PreparedStatement ps =
                             conn.prepareStatement("INSERT INTO user_outbox (user_id) VALUES (?)")) {
                    ps.setObject(1, userId);
                    ps.executeUpdate();
                }
                inserted.countDown();
                mayCommit.await(5, TimeUnit.SECONDS);
                conn.commit();
            } catch (Throwable t) {
                errors.add(t);
                inserted.countDown();
            }
        });
    }

    /** 소비자가 실제로 하는 일: 커서보다 큰 행을 순서대로 읽고 커서를 끝까지 올린다. */
    private List<UUID> drain(long cursor) throws Exception {
        List<UUID> seen = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT seq, user_id FROM user_outbox WHERE seq > ? ORDER BY seq")) {
            ps.setLong(1, cursor);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    seen.add(rs.getObject("user_id", UUID.class));
                }
            }
        }
        return seen;
    }

    private long maxSeq() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(max(seq), 0) FROM user_outbox")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("advisory lock 이 있으면 늦게 커밋되는 트랜잭션이 낮은 시퀀스를 선점하지 못한다")
    void withLock_sequenceFollowsCommitOrder() throws Exception {
        UUID slowUser = UUID.randomUUID();
        UUID fastUser = UUID.randomUUID();
        List<Throwable> errors = new ArrayList<>();
        CountDownLatch slowInserted = new CountDownLatch(1);
        CountDownLatch slowMayCommit = new CountDownLatch(1);

        Thread slow = slowWriter(slowUser, true, slowInserted, slowMayCommit, errors);
        slow.start();
        assertThat(slowInserted.await(5, TimeUnit.SECONDS)).isTrue();

        // 느린 트랜잭션이 아직 커밋을 안 한 상태에서 두 번째 쓰기를 시도한다.
        // 락 때문에 여기서 막히고, 시퀀스를 뽑는 것 자체가 첫 트랜잭션 커밋 뒤로 밀린다.
        CountDownLatch fastInserted = new CountDownLatch(1);
        CountDownLatch fastMayCommit = new CountDownLatch(1);
        Thread fast = slowWriter(fastUser, true, fastInserted, fastMayCommit, errors);
        fast.start();

        // 막혀 있는지 확인 — 막히지 않는다면 락이 동작하지 않는 것이다
        assertThat(fastInserted.await(1, TimeUnit.SECONDS))
                .as("advisory lock 이 두 번째 쓰기를 막아야 한다")
                .isFalse();

        slowMayCommit.countDown();
        slow.join(5000);
        assertThat(fastInserted.await(5, TimeUnit.SECONDS)).isTrue();
        fastMayCommit.countDown();
        fast.join(5000);

        assertThat(errors).isEmpty();
        // 커서 0에서 한 번에 읽어도, 중간에 읽어도 어느 쪽도 건너뛰지 않는다
        assertThat(drain(0)).containsExactly(slowUser, fastUser);
    }

    @Test
    @DisplayName("음성 대조군: 락이 없으면 소비자가 늦게 커밋된 행을 영영 건너뛴다")
    void withoutLock_consumerSkipsRow() throws Exception {
        UUID slowUser = UUID.randomUUID();
        UUID fastUser = UUID.randomUUID();
        List<Throwable> errors = new ArrayList<>();
        CountDownLatch slowInserted = new CountDownLatch(1);
        CountDownLatch slowMayCommit = new CountDownLatch(1);

        Thread slow = slowWriter(slowUser, false, slowInserted, slowMayCommit, errors);
        slow.start();
        assertThat(slowInserted.await(5, TimeUnit.SECONDS)).isTrue();

        // 락이 없으니 두 번째가 먼저 끝난다 — 더 큰 시퀀스를 들고 먼저 보이게 된다
        CountDownLatch fastInserted = new CountDownLatch(1);
        CountDownLatch fastMayCommit = new CountDownLatch(1);
        Thread fast = slowWriter(fastUser, false, fastInserted, fastMayCommit, errors);
        fast.start();
        assertThat(fastInserted.await(5, TimeUnit.SECONDS)).isTrue();
        fastMayCommit.countDown();
        fast.join(5000);

        // 이 시점의 소비자는 fastUser 만 보고 커서를 그 시퀀스로 올린다
        List<UUID> firstPass = drain(0);
        long cursorAfterFirstPass = maxSeq();
        assertThat(firstPass).containsExactly(fastUser);

        // 그 뒤에 느린 트랜잭션이 커밋된다 — 낮은 시퀀스로 뒤늦게 나타난다
        slowMayCommit.countDown();
        slow.join(5000);
        assertThat(errors).isEmpty();

        // 커서가 이미 지나갔으므로 이 행은 다시는 읽히지 않는다.
        // 이게 advisory lock 이 존재하는 이유이고, 캐시 TTL 이 없어진 지금은 영구 발산이 된다.
        assertThat(drain(cursorAfterFirstPass)).isEmpty();
        assertThat(drain(0)).containsExactly(slowUser, fastUser);
    }
}
