package com.planmate.domain.place.enums;

import java.util.Arrays;
import java.util.List;

public enum PlaceCategory {
    ATTRACTION(List.of("12", "14")),
    ACCOMMODATION(List.of("32")),
    RESTAURANT(List.of("39"));

    private final List<String> contentTypeIds;

    PlaceCategory(List<String> contentTypeIds) {
        this.contentTypeIds = contentTypeIds;
    }

    public List<String> contentTypeIds() {
        return contentTypeIds;
    }

    public static PlaceCategory fromContentTypeId(String contentTypeId) {
        return Arrays.stream(values())
                .filter(category -> category.contentTypeIds.contains(contentTypeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 contentTypeId: " + contentTypeId));
    }
}
