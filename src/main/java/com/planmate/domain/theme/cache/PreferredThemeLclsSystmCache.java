package com.planmate.domain.theme.cache;

import com.planmate.domain.theme.entity.PreferredThemeLclsSystmCode;
import com.planmate.domain.theme.repository.PreferredThemeLclsSystmCodeRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * preferredThemeId → TourAPI lclsSystm(레벨+코드) 매핑을 서버 기동 시 인메모리에 전체 로드해서
 * 매 요청마다 DB 왕복 없이 조회한다. DestinationRegionCodeCache와 동일한 패턴(시드 전용 테이블,
 * 재기동 시에만 갱신). project_ai/04_기능/place/04_추천장소_API_인프라설계.md 5장.
 */
@Component
@RequiredArgsConstructor
public class PreferredThemeLclsSystmCache {

    private final PreferredThemeLclsSystmCodeRepository repository;
    private volatile Map<Integer, List<PreferredThemeLclsSystmCode>> cache = Map.of();

    @PostConstruct
    public void load() {
        cache = repository.findAll().stream()
                .collect(Collectors.groupingBy(code -> code.getPreferredTheme().getPreferredThemeId()));
    }

    public List<PreferredThemeLclsSystmCode> get(Integer preferredThemeId) {
        return cache.getOrDefault(preferredThemeId, List.of());
    }
}
