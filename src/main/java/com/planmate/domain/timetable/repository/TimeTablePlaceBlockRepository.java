package com.planmate.domain.timetable.repository;

import com.planmate.domain.timetable.entity.TimeTablePlaceBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalTime;
import java.util.List;

public interface TimeTablePlaceBlockRepository extends JpaRepository<TimeTablePlaceBlock, Long> {

    // 일정표의 블록 목록 (시작 시간 오름차순)
    // 활용 인덱스: uk_time_table_place_block_time UNIQUE (time_table_id, block_start_time) — time_table_id 선두
    List<TimeTablePlaceBlock> findByTimeTableTimeTableIdOrderByBlockStartTime(Long timeTableId);

    // 특정 시작 시간 블록 존재 여부 (시간 중복 early validation)
    boolean existsByTimeTableTimeTableIdAndBlockStartTime(Long timeTableId, LocalTime blockStartTime);

    // 일정표의 모든 블록 삭제 (명시적 처리가 필요한 경우)
    // time_table ON DELETE CASCADE로 자동 처리되므로, 개별 블록 삭제 API가 없다면 미사용
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TimeTablePlaceBlock tb WHERE tb.timeTable.timeTableId = :timeTableId")
    void deleteByTimeTableTimeTableId(@Param("timeTableId") Long timeTableId);
}
