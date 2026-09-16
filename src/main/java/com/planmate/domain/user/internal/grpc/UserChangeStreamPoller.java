package com.planmate.domain.user.internal.grpc;

import build.buf.gen.planmate.internal.v1.InternalUser;
import build.buf.gen.planmate.internal.v1.WatchUserChangesResponse;
import com.planmate.common.util.GravatarHash;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.internal.outbox.UserOutboxEntry;
import com.planmate.domain.user.internal.outbox.UserOutboxProperties;
import com.planmate.domain.user.internal.outbox.UserOutboxRepository;
import com.planmate.domain.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 열려 있는 WatchUserChanges 세션들에 outbox 를 밀어 넣는다.
 *
 * <p><b>왜 푸시 레지스트리가 아니라 폴링인가</b> — 이걸 "최적화"해서 커밋 시점에 메모리
 * 구독자 집합으로 직접 밀어 넣는 구조로 되돌리지 말 것. 이벤트 소스가 공유 테이블(user_outbox)
 * 이라서 Backend-v2 를 여러 대로 늘려도 어느 인스턴스든 재생과 라이브 테일을 모두 제공할 수
 * 있다. 커서는 소비자가 들고 있으므로 어느 인스턴스에 붙든 상관없다. 예전 구현은 구독자
 * 집합이 JVM 안에만 있어서, 여러 대로 늘리면 A 에서 일어난 변경이 B 에 붙은 소비자에게
 * 영영 가지 않았다(예외도 로그도 없이).
 *
 * <p>커밋 후 {@link #wakeUp()} 은 순수 지연 최적화다. 유실돼도 다음 틱이 덮으므로 정합성에
 * 관여하지 않는다 — 그래서 스레드 풀도, 재시도도 필요 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserChangeStreamPoller {

    private final UserOutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final UserOutboxProperties properties;

    private final Set<UserChangeStreamSession> sessions = new CopyOnWriteArraySet<>();
    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "user-change-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick,
                properties.getPollIntervalMs(), properties.getPollIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void register(UserChangeStreamSession session) {
        sessions.add(session);
        // 새 세션은 기다리지 않고 바로 한 번 돌린다 — 스냅샷 요청이면 특히 체감이 크다
        wakeUp();
    }

    /** 커밋 후 호출. 논블로킹이며 실패해도 다음 정기 틱이 덮는다. */
    public void wakeUp() {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        try {
            scheduler.execute(this::tick);
        } catch (Exception e) {
            // 종료 중이거나 큐가 막힌 경우 — 정기 틱으로 수렴하므로 무시한다
            log.debug("사용자 변경 폴러 깨우기 실패: {}", e.getMessage());
        }
    }

    private void tick() {
        for (UserChangeStreamSession session : sessions) {
            if (!session.isActive()) {
                sessions.remove(session);
                continue;
            }
            try {
                pump(session);
            } catch (Exception e) {
                // 한 세션의 실패가 다른 세션을 막지 않게 한다. 커서를 전진시키지 않았으므로
                // 다음 틱에서 같은 지점부터 다시 시도한다 — 유실이 아니다.
                log.warn("사용자 변경 스트림 전송 실패: {}", e.getMessage());
            }
        }
    }

    private void pump(UserChangeStreamSession session) {
        if (session.phase() == UserChangeStreamSession.Phase.SNAPSHOT) {
            pumpSnapshot(session);
            return;
        }
        pumpTail(session);
    }

    private void pumpTail(UserChangeStreamSession session) {
        while (session.isReady()) {
            List<UserOutboxEntry> batch =
                    outboxRepository.findAfter(session.cursor(), properties.getBatchSize());
            if (batch.isEmpty()) {
                return;
            }
            for (UserOutboxEntry entry : batch) {
                if (!session.send(toMessage(entry), entry.seq())) {
                    // 흐름 제어에 걸렸다 — 커서는 마지막으로 성공한 지점에 멈춰 있다.
                    // setOnReadyHandler 가 다음 틱을 부른다.
                    return;
                }
            }
        }
    }

    private void pumpSnapshot(UserChangeStreamSession session) {
        while (session.isReady()) {
            List<User> page = userRepository.findSnapshotPageIncludingDeleted(
                    session.snapshotAfterUserId(), properties.getBatchSize());
            if (page.isEmpty()) {
                session.completeSnapshot();
                return;
            }
            for (User user : page) {
                if (!session.sendSnapshotEntry(toMessage(user, session.cursor()), user.getUserId())) {
                    return;
                }
            }
        }
    }

    private WatchUserChangesResponse toMessage(UserOutboxEntry entry) {
        InternalUser.Builder user = InternalUser.newBuilder()
                .setUserId(entry.userId().toString())
                .setProfilePublic(entry.profilePublic())
                .setDeleted(entry.deleted());
        // proto3 에는 null 이 없다 — 미등록 값은 빈 문자열로 나가고 수신측이 되돌린다
        if (entry.nickname() != null) {
            user.setNickname(entry.nickname());
        }
        if (entry.profileImageUrl() != null) {
            user.setProfileImageUrl(entry.profileImageUrl());
        }
        if (entry.avatarHash() != null) {
            user.setAvatarHash(entry.avatarHash());
        }

        return WatchUserChangesResponse.newBuilder()
                .setUserId(entry.userId().toString())
                .setSequence(entry.seq())
                .setUser(user)
                .build();
    }

    /**
     * 스냅샷 항목. outbox 를 거치지 않으므로 여기서 아바타 해시를 직접 맞춘다.
     *
     * <p>탈퇴 마스킹 분기는 없다 — 회원 탈퇴가 소프트 삭제가 아니라 즉시 물리 삭제이므로
     * (project_ai/04_기능/마이페이지/C_계정_관리_하드삭제_전환.md 1-6절), 이 메서드가 읽는
     * {@code findSnapshotPageIncludingDeleted} 결과에는 애초에 탈퇴 계정이 나오지 않는다.
     */
    private WatchUserChangesResponse toMessage(User user, long snapshotSeq) {
        InternalUser.Builder proto = InternalUser.newBuilder()
                .setUserId(user.getUserId().toString())
                .setProfilePublic(user.isProfilePublic())
                .setDeleted(false)
                .setNickname(user.getNickname());
        if (user.getProfileImageUrl() != null) {
            proto.setProfileImageUrl(user.getProfileImageUrl());
        }
        String avatarHash = GravatarHash.of(user.getEmail());
        if (avatarHash != null) {
            proto.setAvatarHash(avatarHash);
        }

        // 스냅샷 항목은 모두 같은 기준 시퀀스를 달고 나간다. 소비자의 업서트 가드가
        // source_seq <= 로 비교하므로 같은 값끼리도 적용된다.
        return WatchUserChangesResponse.newBuilder()
                .setUserId(user.getUserId().toString())
                .setSequence(snapshotSeq)
                .setUser(proto)
                .build();
    }
}
