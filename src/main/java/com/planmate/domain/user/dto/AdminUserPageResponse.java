package com.planmate.domain.user.dto;

import java.util.List;

/** 관리자 회원 검색 결과 한 페이지 */
public record AdminUserPageResponse(
        List<AdminUserSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
