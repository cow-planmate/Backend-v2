package com.planmate.domain.theme.repository;

import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreferredThemeRepository extends JpaRepository<PreferredTheme, Integer> {

    // 카테고리 필터링 — AttributeConverter가 한국어 DB값으로 자동 변환
    List<PreferredTheme> findByCategory(PreferredThemeCategory category);

    // 전체 목록 → JpaRepository.findAll() 기본 제공 (28개 수준, 인덱스 없이 시퀀셜 스캔)
}
