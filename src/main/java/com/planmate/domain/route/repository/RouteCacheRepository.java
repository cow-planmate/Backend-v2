package com.planmate.domain.route.repository;

import com.planmate.domain.route.entity.RouteCache;
import com.planmate.domain.route.enums.RouteCacheMode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RouteCacheRepository extends JpaRepository<RouteCache, Long> {

    // JPQL 파생 쿼리라 엔티티 속성 타입(RouteCacheMode)을 받는다 — @Convert가 바인딩 시 자동 적용된다.
    Optional<RouteCache> findByFromKeyAndToKeyAndMode(String fromKey, String toKey, RouteCacheMode mode);

    /**
     * 동시 요청이 같은 (from_key, to_key, mode)를 동시에 upsert해도 unique 제약 위반이 나지 않도록
     * 네이티브 ON CONFLICT를 쓴다. JSONB 컬럼에 String 파라미터를 그대로 꽂으면 타입 에러가 나므로
     * CAST(:payload AS jsonb)로 명시 캐스팅한다.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO route_cache (from_key, to_key, mode, response_payload, expired_at) "
            + "VALUES (:fromKey, :toKey, :mode, CAST(:payload AS jsonb), :expiredAt) "
            + "ON CONFLICT (from_key, to_key, mode) DO UPDATE "
            + "SET response_payload = CAST(:payload AS jsonb), expired_at = :expiredAt",
            nativeQuery = true)
    void upsert(@Param("fromKey") String fromKey,
                @Param("toKey") String toKey,
                @Param("mode") String mode,
                @Param("payload") String payload,
                @Param("expiredAt") LocalDateTime expiredAt);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM route_cache WHERE expired_at < :now", nativeQuery = true)
    int deleteExpired(@Param("now") LocalDateTime now);
}
