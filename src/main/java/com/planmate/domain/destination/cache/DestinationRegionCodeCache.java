package com.planmate.domain.destination.cache;

import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.destination.repository.DestinationRegionCodeRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * destinationId → TourAPI 지역코드(lDongRegnCd/lDongSignguCd) 매핑을 서버 기동 시
 * 인메모리에 전체 로드해서 매 요청마다 DB 왕복 없이 조회한다.
 * 원본 데이터는 Flyway 시드로만 채워지는 관리자 큐레이션 테이블이라 재기동 시에만 갱신한다.
 * project_ai/04_기능/place/04_추천장소_API_인프라설계.md 4장.
 */
@Component
@RequiredArgsConstructor
public class DestinationRegionCodeCache {

    private final DestinationRegionCodeRepository repository;
    private volatile Map<Integer, List<DestinationRegionCode>> cache = Map.of();

    @PostConstruct
    public void load() {
        cache = repository.findAll().stream()
                .collect(Collectors.groupingBy(drc -> drc.getDestination().getDestinationId()));
    }

    public List<DestinationRegionCode> get(Integer destinationId) {
        return cache.getOrDefault(destinationId, List.of());
    }
}
