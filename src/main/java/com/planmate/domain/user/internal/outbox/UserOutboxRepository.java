package com.planmate.domain.user.internal.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * outbox 읽기/정리. JPA 를 쓰지 않는 이유는 이 테이블이 엔티티가 아니라 로그이기 때문이다 —
 * 영속성 컨텍스트에 올릴 이유가 없고, 스트리밍 중에 1차 캐시가 커지는 것도 원치 않는다.
 */
@Repository
@RequiredArgsConstructor
public class UserOutboxRepository {

    private static final RowMapper<UserOutboxEntry> ROW_MAPPER = (rs, rowNum) -> new UserOutboxEntry(
            rs.getLong("seq"),
            rs.getObject("user_id", UUID.class),
            rs.getString("nickname"),
            rs.getString("profile_image_url"),
            rs.getString("avatar_hash"),
            rs.getBoolean("profile_public"),
            rs.getBoolean("deleted"));

    private final JdbcTemplate jdbcTemplate;

    /**
     * 커서 다음 행들을 순서대로. 롤백으로 생긴 구멍은 그냥 건너뛰므로 연속성을 가정하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<UserOutboxEntry> findAfter(long seq, int limit) {
        return jdbcTemplate.query("""
                SELECT seq, user_id, nickname, profile_image_url, avatar_hash, profile_public, deleted
                  FROM user_outbox
                 WHERE seq > ?
                 ORDER BY seq
                 LIMIT ?
                """, ROW_MAPPER, seq, limit);
    }

    /** 현재 최대 시퀀스. 스냅샷 시작 기준점이자, 구버전 소비자의 테일 시작점이다. */
    @Transactional(readOnly = true)
    public long currentMaxSeq() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(seq), 0) FROM user_outbox", Long.class);
        return value == null ? 0L : value;
    }

    /**
     * 아직 남아 있는 가장 오래된 시퀀스. 소비자 커서가 이보다 뒤처져 있으면 중간이 이미
     * 정리된 것이므로, 조용히 이어붙이지 말고 스냅샷으로 격하시켜야 한다.
     */
    @Transactional(readOnly = true)
    public long oldestRetainedSeq() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(min(seq), 0) FROM user_outbox", Long.class);
        return value == null ? 0L : value;
    }

    /** 보존 기간이 지난 행 제거. 지워진 구간이 필요한 소비자는 스냅샷 경로로 복구된다. */
    @Transactional
    public int deleteOlderThan(Duration retention) {
        return jdbcTemplate.update(
                "DELETE FROM user_outbox WHERE created_at < now() - make_interval(days => ?)",
                (int) retention.toDays());
    }
}
