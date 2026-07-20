package com.planmate.domain.destination.repository;

import com.planmate.domain.destination.entity.DestinationRegionCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DestinationRegionCodeRepository extends JpaRepository<DestinationRegionCode, Integer> {

    // 전체 목록 → JpaRepository.findAll() 기본 제공 (38행 수준, DestinationRegionCodeCache가 서버 기동 시 1회 전체 로드)
}
