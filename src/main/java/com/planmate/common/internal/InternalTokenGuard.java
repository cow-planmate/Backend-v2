package com.planmate.common.internal;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 내부 공유 토큰이 실제로 "비밀"인지 기동 시점에 확인한다.
 *
 * 이 토큰 하나가 내부 gRPC 사용자 API 전체의 유일한 인증이다.
 * 예전에는 application.yml 에 {@code local-internal-token} 기본값이 있었고 운영 compose 도
 * 같은 값을 그대로 박아 두었기 때문에, 공개 저장소를 읽은 사람이면 누구나 내부 API 를
 * 호출할 수 있었다. 기본값을 지우는 것만으로는 부족하다 — 값이 비면 filter/interceptor 의
 * {@code token.equals("")} 비교가 빈 헤더를 통과시켜 오히려 무인증이 된다.
 *
 * 그래서 기동을 실패시킨다. 잘못된 토큰으로 뜬 서버는 조용히 열려 있지만, 뜨지 못한 서버는
 * 배포 직후에 바로 드러난다.
 */
@Component
public class InternalTokenGuard {

    /** 저장소·문서에 노출된 적이 있는 값. 길이가 충분해도 더 이상 비밀이 아니다. */
    private static final Set<String> LEAKED = Set.of(
            "local-internal-token", "internal-token", "changeme", "secret");

    private static final int MIN_LENGTH = 32;

    @Value("${internal.api-token:}")
    private String internalApiToken;

    @PostConstruct
    void verify() {
        if (internalApiToken == null || internalApiToken.isBlank()) {
            throw new IllegalStateException(
                    "INTERNAL_API_TOKEN 이 설정되지 않았다. 내부 API 인증이 통째로 무력화되므로 기동하지 않는다. "
                            + "생성: openssl rand -hex 32");
        }
        if (LEAKED.contains(internalApiToken)) {
            throw new IllegalStateException(
                    "INTERNAL_API_TOKEN 이 저장소에 공개된 예시 값이다. 새 값으로 교체할 것: openssl rand -hex 32");
        }
        if (internalApiToken.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "INTERNAL_API_TOKEN 이 너무 짧다(최소 " + MIN_LENGTH + "자). 생성: openssl rand -hex 32");
        }
    }
}
