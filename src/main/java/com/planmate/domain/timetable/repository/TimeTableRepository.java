package com.planmate.domain.timetable.repository;

import com.planmate.domain.timetable.entity.TimeTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimeTableRepository extends JpaRepository<TimeTable, Long> {

    // 플랜의 일정표 목록 (날짜순 정렬)
    // 활용 인덱스: uk_time_table_plan_date UNIQUE (plan_id, date) — plan_id 선두
    List<TimeTable> findByPlanPlanIdOrderByDate(UUID planId);

    // 특정 날짜 일정표 단건 조회
    Optional<TimeTable> findByPlanPlanIdAndDate(UUID planId, LocalDate date);

    // 날짜 중복 검증 (서비스 레벨 early validation — DB uk_time_table_plan_date가 최종 방어)
    boolean existsByPlanPlanIdAndDate(UUID planId, LocalDate date);
}
