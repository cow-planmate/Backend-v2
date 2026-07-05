package com.planmate.domain.user.enums.converter;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import com.planmate.domain.user.enums.Gender;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

@Converter(autoApply = true)
public class GenderConverter implements AttributeConverter<Gender, String> {

    @Override
    public String convertToDatabaseColumn(Gender gender) {
        return gender == null ? null : gender.getDbValue();
    }

    @Override
    public Gender convertToEntityAttribute(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        return Arrays.stream(Gender.values())
                .filter(g -> g.getDbValue().equals(dbValue))
                .findFirst()
                .orElseThrow(() -> new PlanMateException(ErrorCode.INVALID_INPUT));
    }
}
