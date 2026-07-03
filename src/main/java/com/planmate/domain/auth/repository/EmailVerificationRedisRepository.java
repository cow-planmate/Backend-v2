package com.planmate.domain.auth.repository;

import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class EmailVerificationRedisRepository {

    private static final String PREFIX = "email_verification:";

    private final RedisTemplate<String, Object> redisTemplate;

    public void saveCode(String email, EmailVerificationPurpose purpose, int code, Duration ttl) {
        redisTemplate.opsForValue().set(key(email, purpose), code, ttl);
    }

    public Optional<Integer> findCode(String email, EmailVerificationPurpose purpose) {
        return Optional.ofNullable((Integer) redisTemplate.opsForValue().get(key(email, purpose)));
    }

    public void delete(String email, EmailVerificationPurpose purpose) {
        redisTemplate.delete(key(email, purpose));
    }

    private String key(String email, EmailVerificationPurpose purpose) {
        return PREFIX + email + "_" + purpose.name();
    }
}
