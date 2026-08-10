package com.planmate.domain.route.entity;

import com.planmate.domain.route.enums.RouteCacheMode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 길찾기(OSRM/ODsay) 인접 쌍 캐시. 조건({@code from_key}/{@code to_key}/{@code mode})과
 * 결과({@code response_payload})를 한 테이블에 담는다 — 조건 1건당 결과가 응답 객체 1개뿐이라
 * v1 Place 캐싱(condition/result 분리)과 달리 페이지네이션이 필요 없다.
 *
 * response_payload는 mode에 따라 실제 타입이 다르므로(driving/foot는 OSRM RoutingResult,
 * transit은 TransitRouteResponse) 엔티티는 raw JSON 문자열로만 들고, 역/직렬화는 서비스 레이어에서
 * mode를 보고 처리한다.
 */
@Entity
@Table(name = "route_cache")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class RouteCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "route_cache_id")
    private Long routeCacheId;

    @Column(name = "from_key", nullable = false, length = 150)
    private String fromKey;

    @Column(name = "to_key", nullable = false, length = 150)
    private String toKey;

    @Convert(converter = com.planmate.domain.route.enums.converter.RouteCacheModeConverter.class)
    @Column(name = "mode", nullable = false, length = 10)
    private RouteCacheMode mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;
}
