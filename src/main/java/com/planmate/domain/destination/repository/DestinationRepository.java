package com.planmate.domain.destination.repository;

import com.planmate.domain.destination.entity.Destination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DestinationRepository extends JpaRepository<Destination, Integer> {

    // 이름으로 단건 조회 (uk_destination_name UNIQUE 인덱스 활용)
    Optional<Destination> findByDestinationName(String destinationName);

    // 전체 목록 → JpaRepository.findAll() 기본 제공
    // 관리자 큐레이션 테이블 (최대 수백 개) → 페이지네이션 불필요
}
