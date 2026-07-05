package com.planmate.domain.oauth.repository;

import com.planmate.domain.oauth.dto.OAuthSignupCache;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OAuthSignupRedisRepository {

    private static final String PREFIX = "oauth:signup:";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final RedisTemplate<String, Object> redisTemplate;

    public String save(OAuthSignupCache cache) {
        String signupId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + signupId, cache, TTL);
        return signupId;
    }

    public Optional<OAuthSignupCache> find(String signupId) {
        return Optional.ofNullable((OAuthSignupCache) redisTemplate.opsForValue().get(PREFIX + signupId));
    }

    public void delete(String signupId) {
        redisTemplate.delete(PREFIX + signupId);
    }
}
