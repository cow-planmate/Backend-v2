package com.planmate.domain.plan.dto;

import com.planmate.domain.timetable.entity.TimeTablePlaceBlock;
import com.planmate.domain.timetable.enums.BlockCategory;

import java.math.BigDecimal;
import java.time.LocalTime;

public record TimetablePlaceBlockDetailDto(
        Long blockId,
        Long timeTableId,
        String placeId,
        String placeName,
        String placeContentTypeId,
        String placeAddress,
        String placeThumbnailUrl,
        String placeCopyrightDivCd,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalTime blockStartTime,
        LocalTime blockEndTime,
        BlockCategory blockCategory,
        String memo
) {
    public static TimetablePlaceBlockDetailDto from(TimeTablePlaceBlock block) {
        return new TimetablePlaceBlockDetailDto(
                block.getBlockId(),
                block.getTimeTable().getTimeTableId(),
                block.getPlaceId(),
                block.getPlaceName(),
                block.getPlaceContentTypeId(),
                block.getPlaceAddress(),
                block.getPlaceThumbnailUrl(),
                block.getPlaceCopyrightDivCd(),
                block.getLatitude(),
                block.getLongitude(),
                block.getBlockStartTime(),
                block.getBlockEndTime(),
                block.getBlockCategory(),
                block.getMemo()
        );
    }
}
