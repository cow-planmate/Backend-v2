package com.planmate.domain.route.enums;

import lombok.Getter;

/**
 * 길찾기 캐시의 이동수단 구분.
 * DB 값은 OSRM profile(driving/foot)과 동일한 소문자 영어를 그대로 쓴다 —
 * plan.transportation_type(대중교통/자가용)과는 다른 개념이며, 이 기능이 이미 wire 값으로
 * 쓰고 있던 "driving"/"foot"에 "transit"만 추가한 것이다.
 */
@Getter
public enum RouteCacheMode {
    DRIVING("driving"),
    FOOT("foot"),
    TRANSIT("transit");

    private final String dbValue;

    RouteCacheMode(String dbValue) {
        this.dbValue = dbValue;
    }

    public static RouteCacheMode fromProfile(String normalizedProfile) {
        return "foot".equals(normalizedProfile) ? FOOT : DRIVING;
    }
}
