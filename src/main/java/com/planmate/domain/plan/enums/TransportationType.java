package com.planmate.domain.plan.enums;

import lombok.Getter;

@Getter
public enum TransportationType {
    PUBLIC("대중교통"),
    PRIVATE("자가용");

    private final String dbValue;

    TransportationType(String dbValue) {
        this.dbValue = dbValue;
    }
}
