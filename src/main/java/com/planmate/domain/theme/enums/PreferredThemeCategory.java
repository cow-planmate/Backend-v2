package com.planmate.domain.theme.enums;

import lombok.Getter;

@Getter
public enum PreferredThemeCategory {
    ATTRACTION("관광지"),
    ACCOMMODATION("숙소"),
    RESTAURANT("식당");

    private final String dbValue;

    PreferredThemeCategory(String dbValue) {
        this.dbValue = dbValue;
    }
}
