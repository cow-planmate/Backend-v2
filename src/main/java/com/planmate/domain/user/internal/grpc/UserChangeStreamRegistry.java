package com.planmate.domain.user.internal.grpc;

import build.buf.gen.planmate.internal.v1.WatchUserChangesResponse;
import io.grpc.stub.ServerCallStreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 열려 있는 WatchUserChanges 스트림을 보관하고 변경 이벤트를 팬아웃한다.
 *
 * gRPC 서비스에서 분리해 둔 이유는 두 가지다.
 * 하나, RPC 처리와 장기 구독자 관리는 수명도 동시성 특성도 다르다.
 * 둘, 발행 쪽(UserProfileChangedListener)이 gRPC 서비스 빈에 직접 의존하지 않게 된다 —
 * 그 빈에 @Async/@Transactional 이 붙어 프록시로 감싸이면 net.devh 의 서비스 등록이
 * 어긋날 수 있어서 애초에 건드리지 않는 편이 낫다.
 *
 * <b>이 서비스가 한 인스턴스로 뜬다는 전제에 기대고 있다.</b> 구독자 목록이 JVM 안에만
 * 있어서, 여러 대로 늘리면 A 인스턴스에서 일어난 변경은 A 에 붙은 구독자에게만 간다.
 * B 에 붙어 있는 소비자는 무효화를 <b>영영 못 받고</b> 예외도 로그도 남지 않는다 —
 * 캐시 TTL 로만 수렴하므로 "가끔 프로필이 늦게 바뀐다"로 보일 뿐 원인을 찾기 어렵다.
 * 스케일아웃 하려면 인스턴스 간 전파(pub/sub 등)를 먼저 넣어야 한다.
 */
@Slf4j
@Component
public class UserChangeStreamRegistry {

    /** 구독자 수가 서비스 인스턴스 수준(한 자릿수)이라 쓰기 드문 CopyOnWriteArraySet 으로 충분하다. */
    private final Set<ServerCallStreamObserver<WatchUserChangesResponse>> watchers = new CopyOnWriteArraySet<>();

    /**
     * 끊긴 옵저버는 반드시 제거해야 한다 — 남겨두면 죽은 스트림에 계속 write 해 힙이 찬다.
     * 취소/종료 어느 쪽으로 끝나든 해제되도록 두 핸들러를 모두 건다.
     */
    public void register(ServerCallStreamObserver<WatchUserChangesResponse> observer) {
        observer.setOnCancelHandler(() -> watchers.remove(observer));
        observer.setOnCloseHandler(() -> watchers.remove(observer));
        watchers.add(observer);
        log.debug("사용자 변경 스트림 구독 시작 (구독자 수={})", watchers.size());
    }

    /**
     * 한 구독자에게 실패해도 나머지에게는 계속 보낸다 — 캐시 무효화는 best-effort 이고,
     * 못 받아도 수신측 캐시 TTL 로 수렴한다.
     */
    public void broadcast(UUID userId) {
        WatchUserChangesResponse event = WatchUserChangesResponse.newBuilder()
                .setUserId(userId.toString())
                .build();

        for (ServerCallStreamObserver<WatchUserChangesResponse> observer : watchers) {
            try {
                // StreamObserver 는 스레드 안전하지 않다. 동시 커밋에서 onNext 가 겹치면
                // 프레임이 섞이므로 옵저버 단위로 직렬화한다.
                synchronized (observer) {
                    if (observer.isCancelled()) {
                        watchers.remove(observer);
                        continue;
                    }
                    // isReady() 가 false 면 이미 밀린 상태다. 그대로 밀어넣으면 느린 구독자 때문에
                    // 서버 힙에 이벤트가 무한히 쌓이므로 이번 알림은 버린다 — 어차피 TTL 로 수렴한다.
                    if (!observer.isReady()) {
                        log.warn("사용자 변경 스트림이 밀려 알림을 건너뛴다 (userId={})", userId);
                        continue;
                    }
                    observer.onNext(event);
                }
            } catch (Exception e) {
                watchers.remove(observer);
                log.warn("사용자 변경 알림 발행 실패 (userId={}): {}", userId, e.getMessage());
            }
        }
    }

    /** 테스트용 — 등록된 구독자 수. */
    int size() {
        return watchers.size();
    }
}
