package com.planmate.domain.user.internal.grpc;

import build.buf.gen.planmate.internal.v1.WatchUserChangesResponse;
import io.grpc.stub.ServerCallStreamObserver;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 열려 있는 WatchUserChanges 스트림 하나. 소비자별 커서와 단계를 들고 있다.
 *
 * <p><b>커서는 onNext 가 성공한 뒤에만 전진한다.</b> 그래서 소비자가 느려 흐름 제어에 걸리거나
 * 스트림이 끊겨도 아무것도 잃지 않는다 — 다음 틱이나 다음 연결에서 같은 지점부터 다시 보낸다.
 * 예전 구현은 흐름 제어에 걸리면 이벤트를 버렸는데, 그건 소비자가 캐시 TTL 로 수렴한다는
 * 전제 위에서만 성립했다. 복제 테이블에는 TTL 이 없어 버린 이벤트가 영구 발산이 된다.
 *
 * <p>스레드 안전성: 폴러 스레드 하나만 {@link #pump}/{@link #pumpSnapshotPage} 를 호출하고,
 * gRPC 스레드는 취소 핸들러에서 {@link #cancel()} 만 건드린다.
 */
public class UserChangeStreamSession {

    /** UUID 최솟값 — 스냅샷 키셋 페이징의 시작점. */
    private static final UUID FIRST_USER_ID = new UUID(0L, 0L);

    public enum Phase {
        /** users 전체를 훑어 보내는 중. 끝나면 snapshot_complete 를 보내고 TAIL 로 넘어간다. */
        SNAPSHOT,
        /** outbox 를 커서 이후로 따라가는 중. */
        TAIL
    }

    private final ServerCallStreamObserver<WatchUserChangesResponse> observer;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private Phase phase;
    /** TAIL 단계의 outbox 커서. 이 값보다 큰 seq 를 보낸다. */
    private long cursor;
    /** SNAPSHOT 단계의 users 키셋 커서. */
    private UUID snapshotAfterUserId = FIRST_USER_ID;
    /** SNAPSHOT 단계가 끝나면 넘어갈 outbox 커서. 스냅샷을 읽기 <b>전</b>에 확보한 값이다. */
    private long snapshotSeq;
    /** TAIL 로 뭔가 보냈고 아직 "따라잡음" 마커로 마감하지 않았다. {@link #sendCaughtUp()} 참고. */
    private boolean catchUpPending;

    public UserChangeStreamSession(ServerCallStreamObserver<WatchUserChangesResponse> observer) {
        this.observer = observer;
    }

    /** 커서 이후부터 라이브로 따라간다. 구버전 소비자와 정상 재연결이 이 경로다. */
    public void startTail(long fromSequence) {
        this.phase = Phase.TAIL;
        this.cursor = fromSequence;
        this.catchUpPending = false;
    }

    /**
     * users 전체를 보낸 뒤 라이브로 넘어간다. 최초 백필과 커서가 보존 기간 밖으로 밀려난
     * 복구가 같은 경로를 쓴다.
     *
     * @param snapshotSeq 스냅샷을 읽기 전에 확보한 outbox 최대 시퀀스. 스냅샷 도중 일어난
     *                    변경은 이 값 이후로 다시 재생되므로 유실되지 않는다(중복은 멱등 업서트가 흡수).
     */
    public void startSnapshot(long snapshotSeq) {
        this.phase = Phase.SNAPSHOT;
        this.snapshotSeq = snapshotSeq;
        this.cursor = snapshotSeq;
    }

    public Phase phase() {
        return phase;
    }

    public long cursor() {
        return cursor;
    }

    public UUID snapshotAfterUserId() {
        return snapshotAfterUserId;
    }

    public boolean isActive() {
        return active.get() && !observer.isCancelled();
    }

    public void cancel() {
        active.set(false);
    }

    /** 소비자가 더 받을 수 있는 상태인지. false 면 이번 틱은 여기서 멈추고 onReady 를 기다린다. */
    public boolean isReady() {
        return observer.isReady();
    }

    /**
     * TAIL 한 건 전송. 성공하면 커서를 전진시킨다.
     *
     * @return 계속 보낼 수 있으면 true, 흐름 제어/종료로 멈춰야 하면 false
     */
    public boolean send(WatchUserChangesResponse message, long seq) {
        if (!isActive() || !observer.isReady()) {
            return false;
        }
        observer.onNext(message);
        this.cursor = seq;
        this.catchUpPending = true;
        return true;
    }

    /**
     * "여기까지가 이번 구간의 끝"이라는 마커. 값 없이 커서만 실어 보낸다.
     *
     * <p><b>없으면 소비자의 읽기 모델이 갱신되지 않는다.</b> 소비자는 트랜잭션 수를 줄이려고
     * 이벤트를 200건씩 모아 반영하는데, TAIL 은 그만큼 몰려 오는 일이 거의 없다. 닉네임 하나가
     * 바뀌면 그 이벤트 한 건이 소비자 버퍼에 앉은 채로 199건을 기다리게 되고, 재연결해도
     * 커서가 그 앞에 있으니 같은 구간을 다시 받아 다시 갇힌다. 실제로 Social 의 복제본이
     * 이 방식으로 8일간 16건 밀린 채 멈춰 있었다.
     *
     * <p>그래서 보낼 것을 다 보낸 시점에 이 마커를 한 번 보내고, 소비자는 이걸 받으면 버퍼를
     * 비운다. 커서는 건드리지 않으므로(이미 마지막 전송에서 전진했다) 재개 의미론은 그대로다.
     *
     * <p>보낼 게 없었던 틱에는 보내지 않는다 — 유휴 상태에서 폴링 주기마다 빈 메시지가 나가면
     * 안 된다. 흐름 제어로 중간에 끊겨도 플래그가 남아 다음 틱에 마감된다.
     *
     * <p>구버전 소비자는 값이 없는 응답을 그냥 건너뛰므로 이 메시지를 받아도 무해하다.
     *
     * @return 계속 보낼 수 있으면 true
     */
    public boolean sendCaughtUp() {
        if (!catchUpPending) {
            return true;
        }
        if (!isActive() || !observer.isReady()) {
            return false;
        }
        observer.onNext(WatchUserChangesResponse.newBuilder().setSequence(cursor).build());
        this.catchUpPending = false;
        return true;
    }

    /** SNAPSHOT 한 건 전송. 커서 대신 키셋 위치를 전진시킨다. */
    public boolean sendSnapshotEntry(WatchUserChangesResponse message, UUID userId) {
        if (!isActive() || !observer.isReady()) {
            return false;
        }
        observer.onNext(message);
        this.snapshotAfterUserId = userId;
        return true;
    }

    /**
     * 스냅샷 종료를 알리고 TAIL 로 전환한다.
     *
     * <p>소비자는 이 메시지를 받은 시점에만 커서를 영속화한다 — 스냅샷 도중에 올리면
     * 크래시했을 때 아직 반영 안 한 사용자를 영영 건너뛰기 때문이다.
     */
    public boolean completeSnapshot() {
        if (!isActive() || !observer.isReady()) {
            return false;
        }
        observer.onNext(WatchUserChangesResponse.newBuilder()
                .setSequence(snapshotSeq)
                .setSnapshotComplete(true)
                .build());
        this.phase = Phase.TAIL;
        this.cursor = snapshotSeq;
        return true;
    }
}
