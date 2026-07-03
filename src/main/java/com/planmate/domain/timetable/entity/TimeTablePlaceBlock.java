package com.planmate.domain.timetable.entity;

import com.planmate.common.entity.BaseTimeEntity;
import com.planmate.domain.timetable.enums.BlockCategory;
import com.planmate.domain.timetable.enums.converter.BlockCategoryConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

@Getter
@Entity
@Table(name = "time_table_place_block")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TimeTablePlaceBlock extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "block_id")
    private Long blockId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "time_table_id", nullable = false)
    private TimeTable timeTable;

    @Column(name = "place_id", length = 100)
    private String placeId;

    @Column(name = "place_name", nullable = false, length = 255)
    private String placeName;

    @Column(name = "place_rating", precision = 3, scale = 1)
    private BigDecimal placeRating;

    @Column(name = "place_address", columnDefinition = "TEXT")
    private String placeAddress;

    @Column(precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "block_start_time", nullable = false)
    private LocalTime blockStartTime;

    @Column(name = "block_end_time", nullable = false)
    private LocalTime blockEndTime;

    @Convert(converter = BlockCategoryConverter.class)
    @Column(name = "block_category", nullable = false, length = 10)
    private BlockCategory blockCategory;

    @Column(columnDefinition = "TEXT")
    private String memo;
}
