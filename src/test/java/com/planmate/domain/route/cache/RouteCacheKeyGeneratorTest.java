package com.planmate.domain.route.cache;

import com.planmate.domain.route.dto.RoutePointDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteCacheKeyGeneratorTest {

    @Test
    @DisplayName("placeId가 있으면 placeId 기준 키를 만든다(좌표는 무시)")
    void key_withPlaceId_usesPlaceIdRegardlessOfCoordinates() {
        RoutePointDto p1 = new RoutePointDto(37.1111, 127.1111, "PLACE_1");
        RoutePointDto p2 = new RoutePointDto(0.0, 0.0, "PLACE_1"); // 좌표가 달라도 placeId가 같으면 같은 키

        assertThat(RouteCacheKeyGenerator.key(p1)).isEqualTo("P:PLACE_1");
        assertThat(RouteCacheKeyGenerator.key(p2)).isEqualTo("P:PLACE_1");
    }

    @Test
    @DisplayName("placeId가 null이면 좌표를 소수 5자리로 반올림한 키로 폴백한다")
    void key_nullPlaceId_fallsBackToRoundedCoordinate() {
        RoutePointDto point = new RoutePointDto(37.123456, 127.123456, null);

        assertThat(RouteCacheKeyGenerator.key(point)).isEqualTo("C:37.12346,127.12346");
    }

    @Test
    @DisplayName("placeId가 빈 문자열/공백이면 좌표 폴백을 쓴다")
    void key_blankPlaceId_fallsBackToCoordinate() {
        RoutePointDto empty = new RoutePointDto(37.5, 127.0, "");
        RoutePointDto blank = new RoutePointDto(37.5, 127.0, "   ");

        // BigDecimal.setScale(5)는 0을 채워서 고정 자릿수로 만든다("37.5" -> "37.50000")
        assertThat(RouteCacheKeyGenerator.key(empty)).isEqualTo("C:37.50000,127.00000");
        assertThat(RouteCacheKeyGenerator.key(blank)).isEqualTo("C:37.50000,127.00000");
    }

    @Test
    @DisplayName("좌표 폴백은 소수 5자리를 넘는 정밀도 차이를 같은 키로 합친다(반올림 오차 이내)")
    void key_coordinatesWithinRoundingTolerance_produceSameKey() {
        RoutePointDto a = new RoutePointDto(37.1234561, 127.1234561, null);
        RoutePointDto b = new RoutePointDto(37.1234564, 127.1234564, null);

        assertThat(RouteCacheKeyGenerator.key(a)).isEqualTo(RouteCacheKeyGenerator.key(b));
    }

    @Test
    @DisplayName("서로 다른 두 지점이면 placeId 기준으로 서로 다른 키가 나온다")
    void key_differentPlaceIds_produceDifferentKeys() {
        RoutePointDto a = new RoutePointDto(37.5, 127.0, "A");
        RoutePointDto b = new RoutePointDto(37.5, 127.0, "B");

        assertThat(RouteCacheKeyGenerator.key(a)).isNotEqualTo(RouteCacheKeyGenerator.key(b));
    }
}
