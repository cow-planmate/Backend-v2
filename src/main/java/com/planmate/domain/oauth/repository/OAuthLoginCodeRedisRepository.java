package com.planmate.domain.oauth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OAuthLoginCodeRedisRepository {

    private static final String PREFIX = "oauth:login:";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final RedisTemplate<String, Object> redisTemplate;

    public String issue(UUID userId) {
        String code = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + code, userId.toString(), TTL);
        return code;
    }

    public Optional<UUID> consume(String code) {
        // getAndDelete로 원자적 read-and-delete(Redis GETDEL, 6.2+) — GET 후 별도 DELETE는
        // 두 호출 사이 레이스로 동시 요청이 같은 1회용 코드를 중복 소비할 수 있어 원자적 연산으로 대체
        return Optional.ofNullable((String) redisTemplate.opsForValue().getAndDelete(PREFIX + code))
                .map(UUID::fromString);
    }
}
