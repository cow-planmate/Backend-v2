package com.planmate.domain.route.enums.converter;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import com.planmate.domain.route.enums.RouteCacheMode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

@Converter(autoApply = true)
public class RouteCacheModeConverter implements AttributeConverter<RouteCacheMode, String> {

    @Override
    public String convertToDatabaseColumn(RouteCacheMode mode) {
        return mode == null ? null : mode.getDbValue();
    }

    @Override
    public RouteCacheMode convertToEntityAttribute(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        return Arrays.stream(RouteCacheMode.values())
                .filter(m -> m.getDbValue().equals(dbValue))
                .findFirst()
                .orElseThrow(() -> new PlanMateException(ErrorCode.INVALID_INPUT));
    }
}
