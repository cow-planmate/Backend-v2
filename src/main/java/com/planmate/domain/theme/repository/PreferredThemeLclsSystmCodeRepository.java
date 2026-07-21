package com.planmate.domain.theme.repository;

import com.planmate.domain.theme.entity.PreferredThemeLclsSystmCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferredThemeLclsSystmCodeRepository extends JpaRepository<PreferredThemeLclsSystmCode, Integer> {

    // 전체 목록 → JpaRepository.findAll() 기본 제공 (25행 수준, PreferredThemeLclsSystmCache가 서버 기동 시 1회 전체 로드)
}
