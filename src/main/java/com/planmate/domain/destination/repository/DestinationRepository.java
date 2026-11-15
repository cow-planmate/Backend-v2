package com.planmate.domain.destination.repository;

import com.planmate.domain.destination.entity.Destination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DestinationRepository extends JpaRepository<Destination, Integer> {

    // 이름으로 단건 조회 — 이름은 부모 안에서만 유일(uk_destination_parent_name)하지만,
    // 현재 노드 이름은 전체에서도 겹치지 않는다(B_계층_상세.md §7-7). 테스트 픽스처 조회용.
    Optional<Destination> findByDestinationName(String destinationName);

    // 전체 노드 + 부모를 한 번에 — 트리·1차 목록을 메모리에서 조립하므로 부모 접근 시 추가 쿼리가 없게 한다.
    // 관리자 큐레이션 테이블(170행) → 페이지네이션 불필요
    @Query("SELECT d FROM Destination d LEFT JOIN FETCH d.parent")
    List<Destination> findAllWithParent();
}
