package com.planmate.domain.theme.repository;

import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserPreferredThemeRepository extends JpaRepository<UserPreferredTheme, Long> {

    // 사용자의 선호 테마 목록 — preferredTheme(이름/카테고리) 즉시 로딩, N+1 방지
    // 활용 인덱스: UNIQUE (user_id, preferred_theme_id) — user_id 선두 → 단독 조회 가능
    @EntityGraph(attributePaths = {"preferredTheme"})
    List<UserPreferredTheme> findByUserUserId(UUID userId);

    // 사용자의 선호 테마 전체 삭제 (재선택 시 — delete-then-insert 패턴)
    // 벌크 DELETE 한 번으로 처리하기 위해 파생 delete 대신 @Modifying + @Query 사용
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserPreferredTheme upt WHERE upt.user.userId = :userId")
    void deleteByUserUserId(@Param("userId") UUID userId);

    // 사용자의 특정 카테고리 선호 테마만 삭제 (다중 카테고리 부분 수정 — 마이페이지 B_프로필_필드_수정.md)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserPreferredTheme upt WHERE upt.user.userId = :userId AND upt.preferredTheme.category = :category")
    void deleteByUserUserIdAndPreferredThemeCategory(@Param("userId") UUID userId, @Param("category") PreferredThemeCategory category);

    // 특정 테마를 선호하는 사용자 수 (AI 추천 가중치 활용)
    // 활용 인덱스: idx_user_preferred_theme_theme_id ON (preferred_theme_id)
    long countByPreferredThemePreferredThemeId(Integer themeId);
}
