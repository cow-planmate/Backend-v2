package com.planmate.domain.timetable.enums;

import lombok.Getter;

@Getter
public enum BlockCategory {
    ATTRACTION("관광지"),
    ACCOMMODATION("숙소"),
    RESTAURANT("식당"),
    FREE("자유"),
    SEARCH("검색");

    private final String dbValue;

    BlockCategory(String dbValue) {
        this.dbValue = dbValue;
    }
}
