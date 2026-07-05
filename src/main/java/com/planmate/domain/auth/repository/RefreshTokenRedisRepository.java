package com.planmate.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRedisRepository {

    private static final String PREFIX = "REFRESHTOKEN:";

    private final RedisTemplate<String, Object> redisTemplate;

    public void save(String token, UUID userId, Duration ttl) {
        // GenericJackson2JsonRedisSerializer는 final 타입(UUID)엔 @class 타입 태그를 안 붙여서
        // 그대로 저장하면 조회 시 String으로 역직렬화되어 캐스팅에 실패한다 - String으로 명시 변환해서 저장
        redisTemplate.opsForValue().set(PREFIX + token, userId.toString(), ttl);
    }

    public Optional<UUID> findUserId(String token) {
        return Optional.ofNullable((String) redisTemplate.opsForValue().get(PREFIX + token))
                .map(UUID::fromString);
    }

    public void delete(String token) {
        redisTemplate.delete(PREFIX + token);
    }
}
