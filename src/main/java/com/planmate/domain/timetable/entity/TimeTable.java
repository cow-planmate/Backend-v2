package com.planmate.domain.timetable.entity;

import com.planmate.common.entity.BaseTimeEntity;
import com.planmate.domain.plan.entity.Plan;
import com.sharedsync.shared.annotation.CacheEntity;
import com.sharedsync.shared.annotation.ParentId;
import jakarta.persistence.Column;
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

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Entity
@Table(name = "time_table")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@CacheEntity
public class TimeTable extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "time_table_id")
    private Long timeTableId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "time_table_start_time", nullable = false)
    private LocalTime timeTableStartTime;

    @Column(name = "time_table_end_time", nullable = false)
    private LocalTime timeTableEndTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    @ParentId(Plan.class)
    private Plan plan;
}
