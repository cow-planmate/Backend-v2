package com.planmate.domain.user.internal.outbox;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * internal.outbox.* 설정. 등록 방식은 OAuthProperties 와 동일하게 맞췄다.
 *
 * pollIntervalMs 와 batchSize 의 곱이 소비자당 초당 전달 상한이다 — 처리량이 늘 때
 * 코드가 아니라 설정으로 조절할 수 있도록 밖으로 뺐다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "internal.outbox")
public class UserOutboxProperties {

    /**
     * 스냅샷 재전송 없이 소비자가 따라잡을 수 있는 최대 정지 시간.
     * 동시에 탈퇴자의 옛 닉네임이 outbox 에 남는 최대 기간이기도 하다.
     */
    private int retentionDays = 14;

    /** 폴링 주기. 커밋 후 wakeUp 이 유실돼도 이 주기로 따라잡으므로 지연 상한 역할을 한다. */
    private long pollIntervalMs = 500;

    /** 한 번의 틱에서 세션당 읽어 보낼 최대 행 수. */
    private int batchSize = 500;
}
