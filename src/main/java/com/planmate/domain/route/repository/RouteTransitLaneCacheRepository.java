package com.planmate.domain.route.repository;

import com.planmate.domain.route.entity.RouteTransitLaneCache;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RouteTransitLaneCacheRepository extends JpaRepository<RouteTransitLaneCache, Long> {

    Optional<RouteTransitLaneCache> findByMapObj(String mapObj);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO route_transit_lane_cache (map_obj, response_payload, expired_at) "
            + "VALUES (:mapObj, CAST(:payload AS jsonb), :expiredAt) "
            + "ON CONFLICT (map_obj) DO UPDATE "
            + "SET response_payload = CAST(:payload AS jsonb), expired_at = :expiredAt",
            nativeQuery = true)
    void upsert(@Param("mapObj") String mapObj,
                @Param("payload") String payload,
                @Param("expiredAt") LocalDateTime expiredAt);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM route_transit_lane_cache WHERE expired_at < :now", nativeQuery = true)
    int deleteExpired(@Param("now") LocalDateTime now);
}
