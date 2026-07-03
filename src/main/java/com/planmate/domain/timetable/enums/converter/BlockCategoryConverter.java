package com.planmate.domain.timetable.enums.converter;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import com.planmate.domain.timetable.enums.BlockCategory;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

@Converter(autoApply = true)
public class BlockCategoryConverter implements AttributeConverter<BlockCategory, String> {

    @Override
    public String convertToDatabaseColumn(BlockCategory category) {
        return category == null ? null : category.getDbValue();
    }

    @Override
    public BlockCategory convertToEntityAttribute(String dbValue) {
        if (dbValue == null) {
            return null;
        }
        return Arrays.stream(BlockCategory.values())
                .filter(c -> c.getDbValue().equals(dbValue))
                .findFirst()
                .orElseThrow(() -> new PlanMateException(ErrorCode.INVALID_INPUT));
    }
}
