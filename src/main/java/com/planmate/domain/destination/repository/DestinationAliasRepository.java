package com.planmate.domain.destination.repository;

import com.planmate.domain.destination.entity.DestinationAlias;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DestinationAliasRepository extends JpaRepository<DestinationAlias, Integer> {

    // 전체 목록 → JpaRepository.findAll() 기본 제공 (수십 행, RegionIndexCache 가 서버 기동 시 1회 전체 로드)
}
