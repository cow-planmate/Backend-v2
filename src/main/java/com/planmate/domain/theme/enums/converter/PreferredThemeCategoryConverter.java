package com.planmate.domain.theme.enums.converter;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

@Converter(autoApply = true)
public class PreferredThemeCategoryConverter implements AttributeConverter<PreferredThemeCategory, String> {

    @Override
    public String convertToDatabaseColumn(PreferredThemeCategory category) {
        return category == null ? null : category.getDbValue();
    }

    @Override
    public PreferredThemeCategory convertToEntityAttribute(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        return Arrays.stream(PreferredThemeCategory.values())
                .filter(c -> c.getDbValue().equals(dbValue))
                .findFirst()
                .orElseThrow(() -> new PlanMateException(ErrorCode.INVALID_INPUT));
    }
}
