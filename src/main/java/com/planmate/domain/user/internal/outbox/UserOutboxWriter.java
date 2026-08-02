package com.planmate.domain.user.internal.outbox;

import com.planmate.common.util.GravatarHash;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.internal.grpc.UserChangeStreamPoller;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사용자 변경을 outbox 테이블에 기록한다. Community가 이 로그를 재생해 자기 읽기 모델을 복제한다.
 *
 * 예전에는 커밋 후 in-memory 레지스트리로 ID만 방송했다. 그건 소비자가 캐시를 쓰고 TTL로
 * 수렴한다는 전제 위에서만 성립했는데, 소비자가 복제 테이블을 갖게 되면 TTL이라는 안전망이
 * 사라진다 — 한 번 놓친 이벤트는 영구 발산이 된다. 그래서 사용자 변경과 같은 트랜잭션에
 * 기록하고, 소비자가 시퀀스 커서로 재생하는 방식으로 바꿨다.
 */
@Component
@RequiredArgsConstructor
public class UserOutboxWriter {

    /**
     * 이 outbox 전용 advisory lock 키. 다른 용도로 pg_advisory_xact_lock 을 쓰게 되면
     * 반드시 다른 상수를 쓸 것 — 같은 키를 쓰면 서로 무관한 두 작업이 직렬화된다.
     */
    private static final long PUBLISH_LOCK_KEY = 4_919_001L;

    private final JdbcTemplate jdbcTemplate;
    private final UserChangeStreamPoller poller;

    /**
     * outbox 행을 기록한다.
     *
     * <p><b>MANDATORY 인 이유</b>: 이 행은 사용자 변경과 반드시 같은 트랜잭션에 있어야 한다.
     * 트랜잭션 밖에서 부르면 조용히 새 트랜잭션이 열려 "사용자는 롤백됐는데 알림만 나가는" 상태가
     * 생기므로, 아예 예외로 터지게 한다.
     *
     * <p><b>호출 위치</b>: 각 트랜잭션의 <b>마지막 쓰기</b>여야 한다. 아래 advisory lock 은
     * 트랜잭션이 끝나야 풀리므로, 이 호출 뒤에 느린 작업(MinIO 업로드/삭제 등)이 오면 그만큼
     * 임계 구역이 늘어나 사용자 변경 전체의 처리량 천장이 내려간다.
     *
     * <p><b>처리량 천장과 탈출구</b>: 아래 락은 사용자 변경을 전역 직렬화한다. 임계 구역이
     * INSERT~COMMIT 뿐이라 대략 500~1000 writes/sec 수준이고, 프로필 변경 빈도를 생각하면
     * 한참 남는다. 정말로 여기서 막히면 락을 걷어내는 대신 소비자 쪽 조회에
     * {@code AND xmin < pg_snapshot_xmin(pg_current_snapshot())} 를 붙이는 방법이 있다 —
     * 아직 커밋이 안 끝난 트랜잭션이 낳을 수 있는 seq 를 아예 읽지 않게 되므로 직렬화가
     * 필요 없어진다(대신 가시성이 진행 중인 최장 트랜잭션만큼 늦어진다). 필요해지기 전에는
     * 넣지 말 것.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(User user) {
        // 락은 여기서, 최대한 늦게 잡는다.
        //
        // 이 락이 없으면 seq 가 커밋 순서와 어긋난다. nextval 은 INSERT 시점에 값을 정하지만
        // 행은 COMMIT 시점에 보이므로, seq=10 을 뽑은 느린 트랜잭션보다 seq=11 이 먼저 커밋되면
        // "seq > 커서" 로 읽는 소비자가 11 을 보고 커서를 올려 10 을 영영 건너뛴다.
        // 조용히 일어나고, TTL 이 없으니 수렴하지도 않는다.
        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(?)", PUBLISH_LOCK_KEY);

        // 탈퇴 계정은 닉네임·이미지·아바타를 비운다. InternalUserResponse.ofDeleted 및
        // proto InternalUser.deleted 주석과 같은 규칙 — 탈퇴 후에도 개인정보가 커뮤니티로
        // 계속 흘러갈 이유가 없다. 소비자는 deleted=true 를 보고 "탈퇴한 사용자"로 표시한다.
        boolean deleted = user.isDeleted();

        jdbcTemplate.update("""
                        INSERT INTO user_outbox
                            (user_id, nickname, profile_image_url, avatar_hash, profile_public, deleted)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                user.getUserId(),
                deleted ? null : user.getNickname(),
                deleted ? null : user.getProfileImageUrl(),
                deleted ? null : GravatarHash.of(user.getEmail()),
                user.isProfilePublic(),
                deleted);

        // 커밋 후에 폴러를 깨운다. 정합성은 위 outbox 행이 이미 책임지므로 이 신호는 순수
        // 지연 최적화다 — 유실돼도 다음 정기 틱이 덮는다. 그래서 스레드 풀도 재시도도 없다.
        //
        // 커밋 전에 깨우면 폴러가 아직 안 보이는 행을 읽으러 갔다가 헛돌 뿐이고,
        // 롤백되면 있지도 않은 변경을 알리게 되므로 반드시 afterCommit 이어야 한다.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    poller.wakeUp();
                }
            });
        }
    }
}
