package com.planmate.domain.chatbot.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.dto.PlanFrameDetailDto;
import com.planmate.domain.plan.dto.TimetableDetailDto;
import com.planmate.domain.plan.dto.TimetablePlaceBlockDetailDto;
import com.planmate.domain.plan.service.PlanSnapshotReader;
import com.planmate.domain.plan.service.PlanSnapshotReader.PlanSnapshot;
import com.planmate.domain.timetable.enums.BlockCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 설계 §11 — 같은 내용은 항상 같은 해시 / 블록 하나만 달라도 다른 해시. */
class PlanContentFingerprintTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final UUID PLAN_ID = UUID.randomUUID();

    private PlanFrameDetailDto frame() {
        return new PlanFrameDetailDto(PLAN_ID, "부산 여행", 20, "부산", 2, 0);
    }

    private TimetableDetailDto timetable() {
        return new TimetableDetailDto(1L, LocalDate.of(2026, 10, 11), LocalTime.of(9, 0), LocalTime.of(20, 0));
    }

    private TimetablePlaceBlockDetailDto block(String placeName) {
        return new TimetablePlaceBlockDetailDto(11L, 1L, "P1", placeName, "12", "addr", "thumb", "Type3",
                BigDecimal.valueOf(35.1), BigDecimal.valueOf(129.0), LocalTime.of(9, 0), LocalTime.of(10, 0),
                BlockCategory.ATTRACTION, "memo");
    }

    @Test
    @DisplayName("같은 내용 → 항상 같은 해시")
    void sameContent_sameHash() {
        GetCompletePlanResponse a = new GetCompletePlanResponse(frame(), List.of(timetable()), List.of(block("경복궁")));
        GetCompletePlanResponse b = new GetCompletePlanResponse(frame(), List.of(timetable()), List.of(block("경복궁")));

        assertThat(PlanContentFingerprint.of(objectMapper, a)).isEqualTo(PlanContentFingerprint.of(objectMapper, b));
    }

    @Test
    @DisplayName("블록 하나만 달라도 다른 해시")
    void differentBlock_differentHash() {
        GetCompletePlanResponse a = new GetCompletePlanResponse(frame(), List.of(timetable()), List.of(block("경복궁")));
        GetCompletePlanResponse b = new GetCompletePlanResponse(frame(), List.of(timetable()), List.of(block("창덕궁")));

        assertThat(PlanContentFingerprint.of(objectMapper, a)).isNotEqualTo(PlanContentFingerprint.of(objectMapper, b));
    }

    @Test
    @DisplayName("GetCompletePlanResponse 와 PlanSnapshot — 같은 내용이면 같은 해시 (셰이프 동일)")
    void responseAndSnapshot_sameContent_sameHash() {
        GetCompletePlanResponse response =
                new GetCompletePlanResponse(frame(), List.of(timetable()), List.of(block("경복궁")));
        PlanSnapshot snapshot = new PlanSnapshot(frame(), List.of(timetable()), List.of(block("경복궁")),
                PlanSnapshotReader.Source.CACHE);

        assertThat(PlanContentFingerprint.of(objectMapper, response))
                .isEqualTo(PlanContentFingerprint.of(objectMapper, snapshot));
    }

    @Test
    @DisplayName("PlanSnapshot 의 source(어디서 읽었는지)는 지문에 들어가지 않는다")
    void snapshotSource_notPartOfHash() {
        PlanSnapshot fromCache = new PlanSnapshot(frame(), List.of(timetable()), List.of(block("경복궁")),
                PlanSnapshotReader.Source.CACHE);
        PlanSnapshot fromDatabase = new PlanSnapshot(frame(), List.of(timetable()), List.of(block("경복궁")),
                PlanSnapshotReader.Source.DATABASE);

        assertThat(PlanContentFingerprint.of(objectMapper, fromCache))
                .isEqualTo(PlanContentFingerprint.of(objectMapper, fromDatabase));
    }

    @Test
    @DisplayName("SHA-256 hex 문자열 (64자)")
    void hashFormat() {
        GetCompletePlanResponse a = new GetCompletePlanResponse(frame(), List.of(), List.of());
        assertThat(PlanContentFingerprint.of(objectMapper, a)).hasSize(64).matches("[0-9a-f]{64}");
    }
}
