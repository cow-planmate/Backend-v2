package com.planmate.domain.user.enums;

import lombok.Getter;

@Getter
public enum Gender {
    MALE("남성"),
    FEMALE("여성"),
    OTHER("기타");

    private final String dbValue;

    Gender(String dbValue) {
        this.dbValue = dbValue;
    }
}
