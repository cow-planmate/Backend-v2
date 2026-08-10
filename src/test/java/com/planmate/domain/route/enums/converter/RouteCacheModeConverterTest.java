package com.planmate.domain.route.enums.converter;

import com.planmate.common.exception.PlanMateException;
import com.planmate.domain.route.enums.RouteCacheMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteCacheModeConverterTest {

    private final RouteCacheModeConverter converter = new RouteCacheModeConverter();

    @Test
    @DisplayName("enum -> DB 값 변환은 소문자 dbValue를 그대로 반환한다")
    void convertToDatabaseColumn_mapsToLowercaseDbValue() {
        assertThat(converter.convertToDatabaseColumn(RouteCacheMode.DRIVING)).isEqualTo("driving");
        assertThat(converter.convertToDatabaseColumn(RouteCacheMode.FOOT)).isEqualTo("foot");
        assertThat(converter.convertToDatabaseColumn(RouteCacheMode.TRANSIT)).isEqualTo("transit");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("DB 값 -> enum 변환은 dbValue 문자열을 정확히 매칭한다")
    void convertToEntityAttribute_mapsFromDbValue() {
        assertThat(converter.convertToEntityAttribute("driving")).isEqualTo(RouteCacheMode.DRIVING);
        assertThat(converter.convertToEntityAttribute("foot")).isEqualTo(RouteCacheMode.FOOT);
        assertThat(converter.convertToEntityAttribute("transit")).isEqualTo(RouteCacheMode.TRANSIT);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("알 수 없는 DB 값이면 PlanMateException(INVALID_INPUT)을 던진다")
    void convertToEntityAttribute_unknownValue_throws() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("걸어서"))
                .isInstanceOf(PlanMateException.class);
    }
}
