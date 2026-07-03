package com.planmate.domain.plan.enums.converter;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import com.planmate.domain.plan.enums.TransportationType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

@Converter(autoApply = true)
public class TransportationTypeConverter implements AttributeConverter<TransportationType, String> {

    @Override
    public String convertToDatabaseColumn(TransportationType type) {
        return type == null ? null : type.getDbValue();
    }

    @Override
    public TransportationType convertToEntityAttribute(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        return Arrays.stream(TransportationType.values())
                .filter(t -> t.getDbValue().equals(dbValue))
                .findFirst()
                .orElseThrow(() -> new PlanMateException(ErrorCode.INVALID_INPUT));
    }
}
