package com.planmate.domain.route.cache;

import com.planmate.domain.route.dto.RoutePointDto;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 길찾기 캐시의 지점 키를 만든다. placeId가 있으면 그대로 쓰고(모든 플랜/사용자가 캐시 공유),
 * 없으면 좌표를 소수 5자리(약 1.1m 오차)로 반올림해 키로 쓴다 — 이 폴백 경로는 서로 다른 지점이
 * 잘못 합쳐지지 않는 정확성을 히트율보다 우선한다.
 */
public final class RouteCacheKeyGenerator {

    private static final int COORDINATE_SCALE = 5;

    private RouteCacheKeyGenerator() {
    }

    public static String key(RoutePointDto point) {
        if (point.getPlaceId() != null && !point.getPlaceId().isBlank()) {
            return "P:" + point.getPlaceId();
        }
        BigDecimal lat = BigDecimal.valueOf(point.getLat()).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal lng = BigDecimal.valueOf(point.getLng()).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        return "C:" + lat + "," + lng;
    }
}
