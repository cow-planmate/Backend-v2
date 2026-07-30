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
        return from(block, block.getTimeTable().getTimeTableId());
    }

    /**
     * timeTableId를 이미 알고 있을 때 쓰는 변환. SharedSync 캐시에서 복원한 블록은
     * timeTable이 지연 프록시라 초기화하면 아직 DB에 없는 신규 타임테이블에서 터진다.
     */
    public static TimetablePlaceBlockDetailDto from(TimeTablePlaceBlock block, Long timeTableId) {
        return new TimetablePlaceBlockDetailDto(
                block.getBlockId(),
                timeTableId,
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
